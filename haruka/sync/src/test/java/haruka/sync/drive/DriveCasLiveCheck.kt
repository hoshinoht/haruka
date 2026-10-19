package haruka.sync.drive

import haruka.sync.remote.CasResult
import haruka.sync.remote.RemoteLayout
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * p3-remote/cas-check: does real Drive honour v2 `If-Match` on the manifest write? Never runs by default; it
 * needs a `drive.appdata` access token (OAuth 2.0 Playground, scope
 * `https://www.googleapis.com/auth/drive.appdata`, valid about 1 h) in `HARUKA_DRIVE_TOKEN`:
 *
 * ```
 * HARUKA_DRIVE_TOKEN=… ./gradlew :haruka:sync:testDebugUnitTest --rerun \
 *     --tests haruka.sync.drive.DriveCasLiveCheck -i
 * ```
 *
 * It works in its own namespace (`app.haruka.cascheck`), deletes its files before and after, and prints each
 * outcome (Committed = 200, Conflict = 412, Ambiguous = 5xx or no response): v3 multipart create, v2 etag + v3
 * content read, a PUT with the fresh etag (expect 200), the same etag again (expect 412), then 8 parallel PUTs
 * with one fresh etag (expect exactly one 200 and seven 412). Anything else means the fallback protocol.
 */
@EnabledIfEnvironmentVariable(named = "HARUKA_DRIVE_TOKEN", matches = ".+")
class DriveCasLiveCheck {

    private val token = System.getenv("HARUKA_DRIVE_TOKEN")!!

    private val tokens = object : AccessTokenProvider {
        override suspend fun accessToken() = token

        override suspend fun invalidate(token: String) {
            error("Drive rejected HARUKA_DRIVE_TOKEN (401)")
        }
    }

    // OkHttp allows 5 concurrent requests per host by default; the race needs all 8 in flight at once.
    private val client = OkHttpClient.Builder()
        .dispatcher(Dispatcher().apply { maxRequestsPerHost = RACERS })
        .build()

    private val store = DriveRemoteStore(client, tokens, RemoteLayout("app.haruka.cascheck"))

    @Test
    fun `v2 If-Match compare-and-swap holds on Drive`() = runBlocking<Unit> {
        store.list().forEach { store.delete(it.id) }
        try {
            report("create (v3 multipart)", store.casHead(null, "v0".encodeToByteArray())) shouldBe
                CasResult.Committed
            val first = store.readHead()!!
            println("cas-check head: file=${first.fileId} etag=${first.etag}")

            report("PUT fresh etag", store.casHead(first, "v1".encodeToByteArray())) shouldBe CasResult.Committed
            report("PUT stale etag", store.casHead(first, "v2".encodeToByteArray())) shouldBe CasResult.Conflict

            val fresh = store.readHead()!!
            fresh.bytes.decodeToString() shouldBe "v1"
            val results = (1..RACERS)
                .map { i -> async { report("race $i", store.casHead(fresh, "race $i".encodeToByteArray())) } }
                .awaitAll()

            results.count { it == CasResult.Committed } shouldBe 1
            results.count { it == CasResult.Conflict } shouldBe RACERS - 1
            val winner = results.indexOf(CasResult.Committed) + 1
            store.readHead()!!.bytes.decodeToString() shouldBe "race $winner"
        } finally {
            store.list().forEach { store.delete(it.id) }
        }
    }

    private fun report(step: String, result: CasResult): CasResult {
        val status = when (result) {
            CasResult.Committed -> "200 Committed"
            CasResult.Conflict -> "412 Conflict"
            is CasResult.Ambiguous -> "Ambiguous (${result.cause})"
        }
        println("cas-check $step: $status")
        return result
    }

    private companion object {
        const val RACERS = 8
    }
}
