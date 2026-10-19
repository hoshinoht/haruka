package haruka.sync.drive

import haruka.sync.remote.CasResult
import haruka.sync.remote.Head
import haruka.sync.remote.RemoteFile
import haruka.sync.remote.RemoteFileGoneException
import haruka.sync.remote.RemoteHttpException
import haruka.sync.remote.RemoteKind
import haruka.sync.remote.RemoteLayout
import haruka.sync.remote.RemoteRef
import haruka.sync.remote.RemoteStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.time.Instant
import kotlin.coroutines.resumeWithException
import kotlin.random.Random

/**
 * [RemoteStore] over the Drive REST API in the hidden `appDataFolder` space (scope `drive.appdata`).
 *
 * Payloads and the first manifest are created with v3. Drive v3 exposes no etag, so the manifest version tag is
 * read from v2 `files.get` and the compare-and-swap is a v2 media upload with `If-Match`; 412 means another
 * device committed first.
 *
 * [client] must have no cache and no logging interceptor: requests carry the bearer token and library data.
 * Every request retries once with a fresh token after 401, and backs off exponentially with jitter (at most
 * [MAX_ATTEMPTS] tries) on 429, 403 `rateLimitExceeded`/`userRateLimitExceeded` and, except for the manifest
 * write, on 5xx and connection failures.
 */
class DriveRemoteStore internal constructor(
    private val client: OkHttpClient,
    private val tokens: AccessTokenProvider,
    private val layout: RemoteLayout,
    private val baseUrl: HttpUrl,
    private val sleep: suspend (Long) -> Unit,
    private val jitterMillis: () -> Long,
) : RemoteStore {

    constructor(client: OkHttpClient, tokens: AccessTokenProvider, layout: RemoteLayout) : this(
        client,
        tokens,
        layout,
        GOOGLE_APIS,
        { delay(it) },
        { Random.nextLong(MAX_JITTER_MILLIS + 1) },
    )

    // The namespace is an applicationId (RemoteLayout validates it), so it never contains a quote to escape.
    private val namespaceQuery =
        "appProperties has { key='${RemoteLayout.PROPERTY_NAMESPACE}' and value='${layout.namespace}' }"
    private val manifestQuery =
        "$namespaceQuery and appProperties has { key='${RemoteLayout.PROPERTY_KIND}' and " +
            "value='${RemoteKind.MANIFEST.wire}' }"

    // OkHttp silently re-sends a request after some connection failures; the manifest write must never be re-sent.
    private val writeOnceClient = client.newBuilder().retryOnConnectionFailure(false).build()

    override suspend fun list(): List<RemoteRef> = io { listFiles(namespaceQuery) }

    override suspend fun download(fileId: String): ByteArray = io {
        val url = v3File(fileId).addQueryParameter("alt", "media").build()
        call({ Request.Builder().url(url) }) { response ->
            when {
                response.isSuccessful -> response.body.bytes()
                response.code == HTTP_NOT_FOUND -> throw RemoteFileGoneException(fileId)
                else -> throw response.failure()
            }
        }
    }

    /**
     * A retried create can leave an orphan copy behind when the first attempt landed but its response was lost;
     * no manifest references it, so the sweep deletes it.
     */
    override suspend fun createImmutable(file: RemoteFile.Payload, bytes: ByteArray): String = io {
        val metadata = metadataOf(file)
        if (bytes.size <= MULTIPART_MAX_BYTES) {
            call({ multipartCreate(metadata, bytes) }) { response ->
                if (response.isSuccessful) response.decode<CreatedFile>().id else throw response.failure()
            }
        } else {
            createResumable(metadata, bytes)
        }
    }

    override suspend fun readHead(): Head? = io {
        val manifest = listFiles(manifestQuery)
            .minWithOrNull(compareBy<RemoteRef> { it.createdAtMillis }.thenBy { it.id })
            ?: return@io null
        // Tag first: a commit landing before the content read leaves the tag stale, so casHead fails safely.
        val tagUrl = v2File(manifest.id).addQueryParameter("fields", "etag").build()
        val etag = call({ Request.Builder().url(tagUrl) }) { response ->
            when {
                response.isSuccessful -> response.decode<EtagOnly>().etag
                response.code == HTTP_NOT_FOUND -> throw RemoteFileGoneException(manifest.id)
                else -> throw response.failure()
            }
        }
        Head(manifest.id, etag, download(manifest.id))
    }

    override suspend fun casHead(expected: Head?, bytes: ByteArray): CasResult = io {
        if (expected == null) createHead(bytes) else replaceHead(expected, bytes)
    }

    override suspend fun delete(fileId: String) = io {
        call({ Request.Builder().url(v3File(fileId).build()).delete() }) { response ->
            if (!response.isSuccessful && response.code != HTTP_NOT_FOUND) throw response.failure()
        }
    }

    /** The signed-in account's address, for display and for revoking access. */
    suspend fun accountEmail(): String = io {
        val url = baseUrl.newBuilder()
            .addPathSegments("drive/v3/about")
            .addQueryParameter("fields", "user(emailAddress)")
            .build()
        call({ Request.Builder().url(url) }) { response ->
            if (response.isSuccessful) response.decode<About>().user.emailAddress else throw response.failure()
        }
    }

    private suspend fun listFiles(query: String): List<RemoteRef> {
        val refs = ArrayList<RemoteRef>()
        var pageToken: String? = null
        do {
            val url = baseUrl.newBuilder()
                .addPathSegments("drive/v3/files")
                .addQueryParameter("spaces", APP_DATA_FOLDER)
                .addQueryParameter("q", query)
                .addQueryParameter("fields", "nextPageToken,files(id,name,size,createdTime)")
                .addQueryParameter("pageSize", "1000")
            pageToken?.let { url.addQueryParameter("pageToken", it) }
            val page = call({ Request.Builder().url(url.build()) }) { response ->
                if (response.isSuccessful) response.decode<FileList>() else throw response.failure()
            }
            page.files.mapTo(refs) {
                RemoteRef(it.id, it.name, it.size?.toLong() ?: 0, Instant.parse(it.createdTime).toEpochMilli())
            }
            pageToken = page.nextPageToken
        } while (pageToken != null)
        return refs
    }

    /**
     * The first manifest. Two devices can both see an empty remote and both create one; readers then take the
     * oldest, and the commit protocol merges and deletes the others.
     */
    private suspend fun createHead(bytes: ByteArray): CasResult {
        if (listFiles(manifestQuery).isNotEmpty()) return CasResult.Conflict
        val metadata = metadataOf(RemoteFile.Manifest)
        return call({ multipartCreate(metadata, bytes) }, ambiguous = { CasResult.Ambiguous(it) }) { response ->
            when {
                response.isSuccessful -> CasResult.Committed
                response.code >= HTTP_SERVER_ERROR -> CasResult.Ambiguous(response.failure())
                else -> throw response.failure()
            }
        }
    }

    private suspend fun replaceHead(expected: Head, bytes: ByteArray): CasResult {
        val url = baseUrl.newBuilder()
            .addPathSegments("upload/drive/v2/files")
            .addPathSegment(expected.fileId)
            .addQueryParameter("uploadType", "media")
            .build()
        val request = {
            Request.Builder().url(url).header("If-Match", expected.etag).put(bytes.toRequestBody(OCTET_STREAM))
        }
        return call(request, ambiguous = { CasResult.Ambiguous(it) }) { response ->
            when {
                response.isSuccessful -> CasResult.Committed
                // 404: the manifest was deleted (a duplicate removed by another device); re-read the head.
                response.code == HTTP_PRECONDITION_FAILED || response.code == HTTP_NOT_FOUND -> CasResult.Conflict
                response.code >= HTTP_SERVER_ERROR -> CasResult.Ambiguous(response.failure())
                else -> throw response.failure()
            }
        }
    }

    private fun metadataOf(file: RemoteFile): String = json.encodeToString(
        FileMetadata.serializer(),
        FileMetadata(layout.nameOf(file), listOf(APP_DATA_FOLDER), layout.appProperties(file)),
    )

    private fun multipartCreate(metadata: String, bytes: ByteArray): Request.Builder {
        val url = baseUrl.newBuilder()
            .addPathSegments("upload/drive/v3/files")
            .addQueryParameter("uploadType", "multipart")
            .addQueryParameter("fields", "id")
            .build()
        val body = MultipartBody.Builder()
            .setType(MULTIPART_RELATED)
            .addPart(metadata.toRequestBody(JSON_UTF8))
            .addPart(bytes.toRequestBody(OCTET_STREAM))
            .build()
        return Request.Builder().url(url).post(body)
    }

    /**
     * Resumable upload for payloads above the multipart limit: one PUT of the whole content; after a dropped
     * connection or 5xx, ask the session how much arrived and send the rest; after a 4xx the session is gone and
     * the upload restarts with a new one.
     */
    private suspend fun createResumable(metadata: String, bytes: ByteArray): String {
        val total = bytes.size.toLong()
        var session = startSession(metadata, total)
        var offset = 0L
        var queryStatus = false
        var failures = 0
        while (true) {
            val step = try {
                if (queryStatus) uploadStatus(session, total) else uploadFrom(session, bytes, offset)
            } catch (e: IOException) {
                UploadStep.Interrupted(e)
            }
            when (step) {
                is UploadStep.Done -> return step.fileId
                is UploadStep.Resume -> {
                    offset = step.offset
                    queryStatus = false
                }
                is UploadStep.Interrupted -> {
                    if (++failures >= MAX_ATTEMPTS) throw step.cause
                    backoff(failures)
                    queryStatus = true
                }
                is UploadStep.Expired -> {
                    if (++failures >= MAX_ATTEMPTS) throw step.cause
                    backoff(failures)
                    session = startSession(metadata, total)
                    offset = 0
                    queryStatus = false
                }
            }
        }
    }

    private suspend fun startSession(metadata: String, total: Long): HttpUrl {
        val url = baseUrl.newBuilder()
            .addPathSegments("upload/drive/v3/files")
            .addQueryParameter("uploadType", "resumable")
            .addQueryParameter("fields", "id")
            .build()
        val request = {
            Request.Builder()
                .url(url)
                .header("X-Upload-Content-Type", OCTET_STREAM.toString())
                .header("X-Upload-Content-Length", total.toString())
                .post(metadata.toRequestBody(JSON_UTF8))
        }
        return call(request) { response ->
            if (!response.isSuccessful) throw response.failure()
            val location = response.header("Location") ?: throw IOException("Resumable session without Location")
            location.toHttpUrl()
        }
    }

    // Session URIs authorize the upload themselves; the documented PUTs carry no Authorization header.
    private suspend fun uploadFrom(session: HttpUrl, bytes: ByteArray, offset: Long): UploadStep {
        val body = bytes.toRequestBody(OCTET_STREAM, offset.toInt(), bytes.size - offset.toInt())
        val request = Request.Builder().url(session).put(body)
        if (offset > 0) request.header("Content-Range", "bytes $offset-${bytes.size - 1}/${bytes.size}")
        return send(request.build()).use { uploadStep(it) }
    }

    private suspend fun uploadStatus(session: HttpUrl, total: Long): UploadStep {
        val request = Request.Builder()
            .url(session)
            .header("Content-Range", "bytes */$total")
            .put(ByteArray(0).toRequestBody())
            .build()
        return send(request).use { uploadStep(it) }
    }

    private fun uploadStep(response: Response): UploadStep = when {
        response.isSuccessful -> UploadStep.Done(response.decode<CreatedFile>().id)
        // 308 Resume Incomplete; Range "bytes=0-42" means 43 bytes arrived, no Range means none.
        response.code == HTTP_RESUME_INCOMPLETE -> UploadStep.Resume(
            response.header("Range")?.substringAfterLast('-')?.toLong()?.plus(1) ?: 0,
        )
        response.code >= HTTP_SERVER_ERROR -> UploadStep.Interrupted(response.failure())
        else -> UploadStep.Expired(response.failure())
    }

    private sealed interface UploadStep {
        class Done(val fileId: String) : UploadStep
        class Resume(val offset: Long) : UploadStep
        class Interrupted(val cause: IOException) : UploadStep
        class Expired(val cause: IOException) : UploadStep
    }

    /**
     * Sends [request] with a bearer token and hands the final response to [handle]. With [ambiguous] set (the
     * manifest write), connection failures and 5xx may have been applied, so they are not retried: connection
     * failures go to [ambiguous] and 5xx to [handle]. Rejections that were certainly not applied (401, rate
     * limits) are retried either way.
     */
    private suspend fun <T> call(
        request: () -> Request.Builder,
        ambiguous: ((IOException) -> T)? = null,
        handle: (Response) -> T,
    ): T {
        var refreshed = false
        var failures = 0
        while (true) {
            val token = tokens.accessToken()
            val response = try {
                val authorized = request().header("Authorization", "Bearer $token").build()
                send(authorized, if (ambiguous != null) writeOnceClient else client)
            } catch (e: IOException) {
                if (ambiguous != null) return ambiguous(e)
                if (++failures >= MAX_ATTEMPTS) throw e
                backoff(failures)
                continue
            }
            if (response.code == HTTP_UNAUTHORIZED && !refreshed) {
                response.close()
                tokens.invalidate(token)
                refreshed = true
                continue
            }
            val retryable = isRateLimited(response) || (ambiguous == null && response.code >= HTTP_SERVER_ERROR)
            if (retryable && ++failures < MAX_ATTEMPTS) {
                response.close()
                backoff(failures)
                continue
            }
            return response.use(handle)
        }
    }

    /** Truncated exponential backoff: 1 s, 2 s, 4 s, … plus up to 1 s of jitter, never above 64 s. */
    private suspend fun backoff(failures: Int) {
        sleep(minOf((1000L shl (failures - 1)) + jitterMillis(), MAX_BACKOFF_MILLIS))
    }

    private fun isRateLimited(response: Response): Boolean = response.code == HTTP_TOO_MANY_REQUESTS ||
        (response.code == HTTP_FORBIDDEN && reasonOf(response) in RATE_LIMIT_REASONS)

    private fun Response.failure() = RemoteHttpException(code, reasonOf(this))

    private fun reasonOf(response: Response): String? = try {
        json.decodeFromString(ErrorResponse.serializer(), response.peekBody(MAX_ERROR_BYTES).string())
            .error.errors.firstOrNull()?.reason
    } catch (_: Exception) {
        null
    }

    private inline fun <reified T> Response.decode(): T = json.decodeFromString(body.string())

    private fun v3File(fileId: String) = baseUrl.newBuilder().addPathSegments("drive/v3/files").addPathSegment(fileId)

    private fun v2File(fileId: String) = baseUrl.newBuilder().addPathSegments("drive/v2/files").addPathSegment(fileId)

    private suspend fun send(request: Request, via: OkHttpClient = client): Response =
        suspendCancellableCoroutine { continuation ->
            val call = via.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (!continuation.isCancelled) continuation.resumeWithException(e)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        continuation.resume(response) { _, value, _ -> value.close() }
                    }
                },
            )
        }

    // Response bodies are read and parsed off the caller's thread.
    private suspend fun <T> io(block: suspend CoroutineScope.() -> T): T = withContext(Dispatchers.IO, block)

    @Serializable
    private class FileList(val files: List<DriveFile> = emptyList(), val nextPageToken: String? = null)

    @Serializable
    private class DriveFile(val id: String, val name: String, val size: String? = null, val createdTime: String)

    @Serializable
    private class CreatedFile(val id: String)

    @Serializable
    private class EtagOnly(val etag: String)

    @Serializable
    private class FileMetadata(val name: String, val parents: List<String>, val appProperties: Map<String, String>)

    @Serializable
    private class About(val user: User)

    @Serializable
    private class User(val emailAddress: String)

    @Serializable
    private class ErrorResponse(val error: ErrorDetail)

    @Serializable
    private class ErrorDetail(val errors: List<ErrorItem> = emptyList())

    @Serializable
    private class ErrorItem(val reason: String? = null)

    companion object {
        /** Tries per request, the first included; the CAS write is never retried after an ambiguous failure. */
        const val MAX_ATTEMPTS = 6

        /** Drive documents multipart for files of 5 MB or less and resumable uploads above. */
        const val MULTIPART_MAX_BYTES = 5_000_000

        private const val MAX_BACKOFF_MILLIS = 64_000L
        private const val MAX_JITTER_MILLIS = 1_000L
        private const val MAX_ERROR_BYTES = 64L * 1024

        private const val APP_DATA_FOLDER = "appDataFolder"
        private val RATE_LIMIT_REASONS = setOf("rateLimitExceeded", "userRateLimitExceeded")

        private const val HTTP_RESUME_INCOMPLETE = 308
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_NOT_FOUND = 404
        private const val HTTP_PRECONDITION_FAILED = 412
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private const val HTTP_SERVER_ERROR = 500

        private val GOOGLE_APIS = "https://www.googleapis.com/".toHttpUrl()
        private val JSON_UTF8 = "application/json; charset=UTF-8".toMediaType()
        private val OCTET_STREAM = "application/octet-stream".toMediaType()
        private val MULTIPART_RELATED = "multipart/related".toMediaType()

        private val json = Json { ignoreUnknownKeys = true }
    }
}
