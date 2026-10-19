package haruka.sync.drive

import haruka.sync.remote.CasResult
import haruka.sync.remote.Head
import haruka.sync.remote.RemoteFile
import haruka.sync.remote.RemoteFileGoneException
import haruka.sync.remote.RemoteHttpException
import haruka.sync.remote.RemoteLayout
import haruka.sync.remote.RemoteRef
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartReader
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.InterruptedIOException
import java.time.Instant
import java.util.concurrent.TimeUnit

/** Per-request assertions (method, path, query, headers, body) and the retry rules of [DriveRemoteStore]. */
class DriveRemoteStoreTest {

    private val server = MockWebServer().apply { start() }
    private val tokens = TestTokens("t1", "t2")
    private val delays = mutableListOf<Long>()
    private val layout = RemoteLayout("app.haruka.test")

    private fun store(client: OkHttpClient = OkHttpClient(), jitter: Long = 0) =
        DriveRemoteStore(client, tokens, layout, server.url("/"), sleep = { delays += it }, jitterMillis = { jitter })

    @AfterEach
    fun stop() {
        server.close()
    }

    private fun json(body: String, code: Int = 200) = MockResponse.Builder()
        .code(code)
        .addHeader("Content-Type", "application/json; charset=UTF-8")
        .body(body)
        .build()

    private fun file(id: String, createdTime: String) =
        """{"id":"$id","name":"name-$id","size":"10","createdTime":"$createdTime"}"""

    private fun take(): RecordedRequest = server.takeRequest(5, TimeUnit.SECONDS)!!

    private fun parts(request: RecordedRequest): List<Pair<String?, ByteArray>> {
        val boundary = request.headers["Content-Type"]!!.toMediaType().parameter("boundary")!!
        val reader = MultipartReader(Buffer().write(request.body!!.toByteArray()), boundary)
        return generateSequence { reader.nextPart() }
            .map { it.headers["Content-Type"] to it.body.readByteArray() }
            .toList()
    }

    private val namespaceQuery = "appProperties has { key='ns' and value='app.haruka.test' }"
    private val manifestQuery = "$namespaceQuery and appProperties has { key='kind' and value='manifest' }"

    @Test
    fun `list queries appDataFolder by namespace and follows every page`() = runBlocking<Unit> {
        server.enqueue(json("""{"files":[${file("a", "2026-10-19T01:00:00Z")}],"nextPageToken":"p2"}"""))
        server.enqueue(json("""{"files":[${file("b", "2026-10-19T02:00:00.500Z")}]}"""))

        store().list() shouldBe listOf(
            RemoteRef("a", "name-a", 10, Instant.parse("2026-10-19T01:00:00Z").toEpochMilli()),
            RemoteRef("b", "name-b", 10, Instant.parse("2026-10-19T02:00:00.500Z").toEpochMilli()),
        )

        listOf(null, "p2").forEach { pageToken ->
            val request = take()
            request.method shouldBe "GET"
            request.url.encodedPath shouldBe "/drive/v3/files"
            request.url.queryParameter("spaces") shouldBe "appDataFolder"
            request.url.queryParameter("q") shouldBe namespaceQuery
            request.url.queryParameter("fields") shouldBe "nextPageToken,files(id,name,size,createdTime)"
            request.url.queryParameter("pageSize") shouldBe "1000"
            request.url.queryParameter("pageToken") shouldBe pageToken
            request.headers["Authorization"] shouldBe "Bearer t1"
        }
    }

    @Test
    fun `readHead reads the v2 etag before the content of the oldest manifest`() = runBlocking<Unit> {
        server.enqueue(
            json(
                """{"files":[${file("m3", "2026-10-19T02:00:00Z")},${file("m2", "2026-10-19T01:00:00Z")},""" +
                    """${file("m1", "2026-10-19T01:00:00Z")}]}""",
            ),
        )
        server.enqueue(json("""{"etag":"\"tag-1\""}"""))
        server.enqueue(MockResponse.Builder().body("manifest").build())

        store().readHead() shouldBe Head("m1", "\"tag-1\"", "manifest".encodeToByteArray())

        take().url.queryParameter("q") shouldBe manifestQuery
        take().let {
            it.method shouldBe "GET"
            it.url.encodedPath shouldBe "/drive/v2/files/m1"
            it.url.queryParameter("fields") shouldBe "etag"
        }
        take().let {
            it.method shouldBe "GET"
            it.url.encodedPath shouldBe "/drive/v3/files/m1"
            it.url.queryParameter("alt") shouldBe "media"
        }
    }

    @Test
    fun `readHead of a remote without manifest is null after one list`() = runBlocking<Unit> {
        server.enqueue(json("""{"files":[]}"""))

        store().readHead().shouldBeNull()
        server.requestCount shouldBe 1
    }

    @Test
    fun `casHead puts the content to the v2 upload URI with If-Match and maps 412 to Conflict`() =
        runBlocking<Unit> {
            val head = Head("m1", "\"tag-1\"", ByteArray(0))
            server.enqueue(json("""{"id":"m1"}"""))
            server.enqueue(FakeDrive.driveError(412, "conditionNotMet"))

            store().casHead(head, "next".encodeToByteArray()) shouldBe CasResult.Committed
            store().casHead(head, "next".encodeToByteArray()) shouldBe CasResult.Conflict

            repeat(2) {
                val request = take()
                request.method shouldBe "PUT"
                request.url.encodedPath shouldBe "/upload/drive/v2/files/m1"
                request.url.queryParameter("uploadType") shouldBe "media"
                request.headers["If-Match"] shouldBe "\"tag-1\""
                request.headers["Content-Type"] shouldBe "application/octet-stream"
                request.headers["Authorization"] shouldBe "Bearer t1"
                request.body!!.utf8() shouldBe "next"
            }
        }

    @Test
    fun `casHead maps a deleted manifest to Conflict`() = runBlocking<Unit> {
        server.enqueue(FakeDrive.driveError(404, "notFound"))

        store().casHead(Head("m1", "\"t\"", ByteArray(0)), byteArrayOf(1)) shouldBe CasResult.Conflict
    }

    @Test
    fun `casHead without a head creates the manifest by v3 multipart upload`() = runBlocking<Unit> {
        server.enqueue(json("""{"files":[]}"""))
        server.enqueue(json("""{"id":"m1"}"""))

        store().casHead(null, "first".encodeToByteArray()) shouldBe CasResult.Committed

        take().url.queryParameter("q") shouldBe manifestQuery
        val create = take()
        create.method shouldBe "POST"
        create.url.encodedPath shouldBe "/upload/drive/v3/files"
        create.url.queryParameter("uploadType") shouldBe "multipart"
        create.headers["Content-Type"]!! shouldStartWith "multipart/related; boundary="
        val (metadata, media) = parts(create)
        metadata.first shouldBe "application/json; charset=UTF-8"
        val json = Json.parseToJsonElement(metadata.second.decodeToString()).jsonObject
        json["name"]!!.jsonPrimitive.content shouldBe "app.haruka.test-manifest"
        json["parents"]!!.jsonArray.map { it.jsonPrimitive.content } shouldBe listOf("appDataFolder")
        json["appProperties"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content } shouldBe
            mapOf("ns" to "app.haruka.test", "kind" to "manifest")
        media.first shouldBe "application/octet-stream"
        media.second.decodeToString() shouldBe "first"
    }

    @Test
    fun `casHead without a head conflicts when a manifest already exists`() = runBlocking<Unit> {
        server.enqueue(json("""{"files":[${file("m1", "2026-10-19T01:00:00Z")}]}"""))

        store().casHead(null, byteArrayOf(1)) shouldBe CasResult.Conflict
        server.requestCount shouldBe 1
    }

    @Test
    fun `createImmutable uploads small payloads multipart with name and app properties`() = runBlocking<Unit> {
        val bucket = RemoteFile.Bucket(7, "0123456789abcdef", encrypted = true)
        server.enqueue(json("""{"id":"p1"}"""))

        store().createImmutable(bucket, byteArrayOf(1, 2, 3)) shouldBe "p1"

        val (metadata, media) = parts(take())
        val json = Json.parseToJsonElement(metadata.second.decodeToString()).jsonObject
        json["name"]!!.jsonPrimitive.content shouldBe "app.haruka.test-b007-0123456789abcdef.pb.gz.enc"
        json["appProperties"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content } shouldBe
            mapOf("ns" to "app.haruka.test", "kind" to "bucket")
        media.second.toList() shouldBe listOf<Byte>(1, 2, 3)
    }

    @Test
    fun `createImmutable uploads large payloads resumably and resumes after an interruption`() =
        runBlocking<Unit> {
            val size = DriveRemoteStore.MULTIPART_MAX_BYTES + 1
            val bytes = ByteArray(size) { it.toByte() }
            server.enqueue(
                MockResponse.Builder().addHeader("Location", server.url("/upload/session?upload_id=s1")).build(),
            )
            server.enqueue(FakeDrive.driveError(503, "backendError"))
            server.enqueue(MockResponse.Builder().code(308).addHeader("Range", "bytes=0-999").build())
            server.enqueue(json("""{"id":"big"}"""))

            store().createImmutable(RemoteFile.Prefs("0123456789abcdef", encrypted = false), bytes) shouldBe "big"

            take().let {
                it.method shouldBe "POST"
                it.url.encodedPath shouldBe "/upload/drive/v3/files"
                it.url.queryParameter("uploadType") shouldBe "resumable"
                it.headers["X-Upload-Content-Type"] shouldBe "application/octet-stream"
                it.headers["X-Upload-Content-Length"] shouldBe size.toString()
                it.headers["Content-Type"] shouldBe "application/json; charset=UTF-8"
                Json.parseToJsonElement(it.body!!.utf8()).jsonObject["name"]!!.jsonPrimitive.content shouldBe
                    "app.haruka.test-prefs-0123456789abcdef.pb.gz"
            }
            take().let {
                it.method shouldBe "PUT"
                it.url.queryParameter("upload_id") shouldBe "s1"
                it.headers["Content-Range"].shouldBeNull()
                it.bodySize shouldBe size.toLong()
            }
            take().let {
                it.method shouldBe "PUT"
                it.headers["Content-Range"] shouldBe "bytes */$size"
                it.bodySize shouldBe 0L
            }
            take().let {
                it.method shouldBe "PUT"
                it.headers["Content-Range"] shouldBe "bytes 1000-${size - 1}/$size"
                it.body!!.toByteArray().toList() shouldBe bytes.drop(1000)
            }
            delays shouldBe listOf(1000L)
        }

    @Test
    fun `download maps 404 to RemoteFileGoneException and delete treats it as done`() = runBlocking<Unit> {
        server.enqueue(FakeDrive.driveError(404, "notFound"))
        server.enqueue(FakeDrive.driveError(404, "notFound"))

        shouldThrow<RemoteFileGoneException> { store().download("p1") }.fileId shouldBe "p1"
        store().delete("p1")

        take().url.encodedPath shouldBe "/drive/v3/files/p1"
        take().let {
            it.method shouldBe "DELETE"
            it.url.encodedPath shouldBe "/drive/v3/files/p1"
        }
    }

    @Test
    fun `401 invalidates the token and retries once with a fresh one`() = runBlocking<Unit> {
        server.enqueue(FakeDrive.driveError(401, "authError"))
        server.enqueue(MockResponse.Builder().body("bytes").build())

        store().download("p1").decodeToString() shouldBe "bytes"

        tokens.invalidated shouldBe listOf("t1")
        take().headers["Authorization"] shouldBe "Bearer t1"
        take().headers["Authorization"] shouldBe "Bearer t2"
        delays shouldBe emptyList()
    }

    @Test
    fun `a second 401 fails without further retries`() = runBlocking<Unit> {
        server.enqueue(FakeDrive.driveError(401, "authError"))
        server.enqueue(FakeDrive.driveError(401, "authError"))

        val error = shouldThrow<RemoteHttpException> { store().download("p1") }

        error.code shouldBe 401
        error.reason shouldBe "authError"
        server.requestCount shouldBe 2
    }

    @Test
    fun `429 and rate-limit 403 back off exponentially with jitter`() = runBlocking<Unit> {
        server.enqueue(FakeDrive.driveError(429, "rateLimitExceeded"))
        server.enqueue(FakeDrive.driveError(403, "userRateLimitExceeded"))
        server.enqueue(FakeDrive.driveError(403, "rateLimitExceeded"))
        server.enqueue(MockResponse.Builder().body("bytes").build())

        store(jitter = 250).download("p1").decodeToString() shouldBe "bytes"

        delays shouldBe listOf(1250L, 2250L, 4250L)
        server.requestCount shouldBe 4
    }

    @Test
    fun `server errors stop after six tries`() = runBlocking<Unit> {
        repeat(DriveRemoteStore.MAX_ATTEMPTS) { server.enqueue(FakeDrive.driveError(503, "backendError")) }

        shouldThrow<RemoteHttpException> { store().list() }.code shouldBe 503

        delays shouldBe listOf(1000L, 2000L, 4000L, 8000L, 16000L)
        server.requestCount shouldBe DriveRemoteStore.MAX_ATTEMPTS
    }

    @Test
    fun `other 403 reasons are not retried`() = runBlocking<Unit> {
        server.enqueue(FakeDrive.driveError(403, "appNotAuthorizedToFile"))

        shouldThrow<RemoteHttpException> { store().download("p1") }.reason shouldBe "appNotAuthorizedToFile"
        server.requestCount shouldBe 1
    }

    @Test
    fun `casHead never retries a server error`() = runBlocking<Unit> {
        server.enqueue(FakeDrive.driveError(503, "backendError"))

        val result = store().casHead(Head("m1", "\"t\"", ByteArray(0)), byteArrayOf(1))

        val cause = result.shouldBeInstanceOf<CasResult.Ambiguous>().cause
        cause.shouldBeInstanceOf<RemoteHttpException>().code shouldBe 503
        server.requestCount shouldBe 1
        delays shouldBe emptyList()
    }

    @Test
    fun `casHead never retries after a timeout`() = runBlocking<Unit> {
        val client = OkHttpClient.Builder().readTimeout(200, TimeUnit.MILLISECONDS).build()
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.Stall).build())

        val result = store(client).casHead(Head("m1", "\"t\"", ByteArray(0)), byteArrayOf(1))

        // OkHttp reports a read timeout as SocketTimeoutException, an InterruptedIOException.
        result.shouldBeInstanceOf<CasResult.Ambiguous>().cause.shouldBeInstanceOf<InterruptedIOException>()
        server.requestCount shouldBe 1
    }

    @Test
    fun `casHead retries rate limits, which are never applied`() = runBlocking<Unit> {
        server.enqueue(FakeDrive.driveError(429, "rateLimitExceeded"))
        server.enqueue(json("""{"id":"m1"}"""))

        store().casHead(Head("m1", "\"t\"", ByteArray(0)), byteArrayOf(1)) shouldBe CasResult.Committed
        delays shouldBe listOf(1000L)
    }

    @Test
    fun `accountEmail reads about with the user field`() = runBlocking<Unit> {
        server.enqueue(json("""{"user":{"emailAddress":"me@example.com"}}"""))

        store().accountEmail() shouldBe "me@example.com"

        take().let {
            it.url.encodedPath shouldBe "/drive/v3/about"
            it.url.queryParameter("fields") shouldBe "user(emailAddress)"
        }
    }
}
