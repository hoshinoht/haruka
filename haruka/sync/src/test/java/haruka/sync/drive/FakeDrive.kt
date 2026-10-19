package haruka.sync.drive

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartReader
import okio.Buffer
import java.time.Instant

/**
 * The slice of the Drive REST API that [DriveRemoteStore] uses, in memory, for the contract suite: v3 list with
 * `appProperties has` filters and paging ([pageSize] files per page, whatever the client asks), v3 media
 * download, v3 multipart create, v2 etag get, v2 media update with `If-Match`, v3 delete.
 */
class FakeDrive(private val pageSize: Int) : Dispatcher() {

    class File(
        val id: String,
        val name: String,
        val appProperties: Map<String, String>,
        val bytes: ByteArray,
        val createdTime: Instant,
        val generation: Long,
    ) {
        val etag get() = "\"$id-$generation\""
    }

    private val files = LinkedHashMap<String, File>()
    private var lastId = 0
    private var lastGeneration = 0L
    private var clock = Instant.parse("2026-10-19T00:00:00Z")

    /** Runs once, right before the next media download of the given file is served. */
    var beforeNextDownload: ((fileId: String) -> Unit)? = null

    @Synchronized
    fun add(name: String, appProperties: Map<String, String>, bytes: ByteArray, createdTime: Instant? = null): String {
        val id = "d" + (++lastId).toString().padStart(4, '0')
        val time = createdTime ?: clock.also { clock = clock.plusSeconds(1) }
        files[id] = File(id, name, appProperties, bytes, time, ++lastGeneration)
        return id
    }

    @Synchronized
    fun overwrite(fileId: String, bytes: ByteArray) {
        val file = files.getValue(fileId)
        files[fileId] = File(file.id, file.name, file.appProperties, bytes, file.createdTime, ++lastGeneration)
    }

    @Synchronized
    override fun dispatch(request: RecordedRequest): MockResponse {
        if (request.headers["Authorization"]?.startsWith("Bearer ") != true) return driveError(401, "authError")
        val path = request.url.pathSegments
        val method = request.method
        return when {
            method == "GET" && path == listOf("drive", "v3", "files") -> list(request)
            method == "GET" && path.size == 4 && path.take(
                3,
            ) == listOf("drive", "v3", "files") -> media(request, path[3])
            method == "GET" && path.size == 4 && path.take(
                3,
            ) == listOf("drive", "v2", "files") -> etag(request, path[3])
            method == "POST" && path == listOf("upload", "drive", "v3", "files") -> create(request)
            method == "PUT" && path.size == 5 && path.take(4) == listOf("upload", "drive", "v2", "files") ->
                update(request, path[4])
            method == "DELETE" && path.size == 4 && path.take(3) == listOf("drive", "v3", "files") -> delete(path[3])
            else -> driveError(400, "badRequest")
        }
    }

    private fun list(request: RecordedRequest): MockResponse {
        if (request.url.queryParameter("spaces") != "appDataFolder") return driveError(400, "badRequest")
        val filters = FILTER.findAll(request.url.queryParameter("q").orEmpty())
            .map { it.groupValues[1] to it.groupValues[2] }
            .toList()
        val matching = files.values.filter { file -> filters.all { (k, v) -> file.appProperties[k] == v } }
        val start = request.url.queryParameter("pageToken")?.toInt() ?: 0
        val page = matching.drop(start).take(pageSize)
        val body = buildJsonObject {
            put(
                "files",
                buildJsonArray {
                    page.forEach { file ->
                        add(
                            buildJsonObject {
                                put("id", file.id)
                                put("name", file.name)
                                put("size", file.bytes.size.toString())
                                put("createdTime", file.createdTime.toString())
                            },
                        )
                    }
                },
            )
            if (start + pageSize < matching.size) put("nextPageToken", (start + pageSize).toString())
        }
        return json(body)
    }

    private fun media(request: RecordedRequest, fileId: String): MockResponse {
        if (request.url.queryParameter("alt") != "media") return driveError(400, "badRequest")
        beforeNextDownload?.let {
            beforeNextDownload = null
            it(fileId)
        }
        val file = files[fileId] ?: return driveError(404, "notFound")
        return MockResponse.Builder().body(Buffer().write(file.bytes)).build()
    }

    private fun etag(request: RecordedRequest, fileId: String): MockResponse {
        if (request.url.queryParameter("fields") != "etag") return driveError(400, "badRequest")
        val file = files[fileId] ?: return driveError(404, "notFound")
        return json(buildJsonObject { put("etag", file.etag) })
    }

    private fun create(request: RecordedRequest): MockResponse {
        if (request.url.queryParameter("uploadType") != "multipart") return driveError(400, "badRequest")
        val boundary = request.headers["Content-Type"]!!.toMediaType().parameter("boundary")!!
        val reader = MultipartReader(Buffer().write(request.body!!.toByteArray()), boundary)
        val metadata = Json.parseToJsonElement(reader.nextPart()!!.body.readUtf8()).jsonObject
        val bytes = reader.nextPart()!!.body.readByteArray()
        if (metadata["parents"]!!.jsonArray != JsonArray(listOf(JsonPrimitive("appDataFolder")))) {
            return driveError(400, "badRequest")
        }
        val properties = (metadata["appProperties"] as JsonObject).mapValues { it.value.jsonPrimitive.content }
        val id = add(metadata["name"]!!.jsonPrimitive.content, properties, bytes)
        return json(buildJsonObject { put("id", id) })
    }

    private fun update(request: RecordedRequest, fileId: String): MockResponse {
        if (request.url.queryParameter("uploadType") != "media") return driveError(400, "badRequest")
        val file = files[fileId] ?: return driveError(404, "notFound")
        val ifMatch = request.headers["If-Match"]
        if (ifMatch != null && ifMatch != file.etag) return driveError(412, "conditionNotMet")
        overwrite(fileId, request.body?.toByteArray() ?: ByteArray(0))
        return json(buildJsonObject { put("id", fileId) })
    }

    private fun delete(fileId: String): MockResponse {
        files.remove(fileId) ?: return driveError(404, "notFound")
        return MockResponse.Builder().code(204).build()
    }

    private fun json(body: JsonObject) = MockResponse.Builder()
        .addHeader("Content-Type", "application/json; charset=UTF-8")
        .body(body.toString())
        .build()

    companion object {
        private val FILTER = Regex("""appProperties has \{ key='([^']*)' and value='([^']*)' \}""")

        /** A Drive error response with the JSON shape of the Drive error guide. */
        fun driveError(code: Int, reason: String): MockResponse = MockResponse.Builder()
            .code(code)
            .addHeader("Content-Type", "application/json; charset=UTF-8")
            .body("""{"error":{"code":$code,"message":"$reason","errors":[{"domain":"global","reason":"$reason"}]}}""")
            .build()
    }
}

/** Hands out [tokens] in order: each 401 invalidation moves to the next one. */
class TestTokens(private vararg val tokens: String) : AccessTokenProvider {
    val invalidated = mutableListOf<String>()

    override suspend fun accessToken(): String = tokens[minOf(invalidated.size, tokens.lastIndex)]

    override suspend fun invalidate(token: String) {
        invalidated += token
    }
}
