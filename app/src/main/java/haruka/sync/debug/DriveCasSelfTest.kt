package haruka.sync.debug

import haruka.sync.drive.DriveRemoteStore
import haruka.sync.remote.CasResult
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * p3-remote/cas-check on a device: does real Drive honour v2 `If-Match` on the manifest write? Mirrors
 * `DriveCasLiveCheck` in :haruka:sync tests: v3 multipart create, v2 etag + v3 content read, a PUT with the fresh
 * etag (expect 200), the same etag again (expect 412), then [RACERS] parallel PUTs with one fresh etag (expect
 * exactly one 200, the rest 412).
 *
 * It deletes every file in [store]'s namespace before and after, so [store] must use a namespace of its own.
 * [store]'s OkHttp dispatcher must allow [RACERS] concurrent requests per host.
 */
class DriveCasSelfTest(
    private val store: DriveRemoteStore,
    private val log: (String) -> Unit,
) {

    /** True when every expectation held. */
    suspend fun run(): Boolean {
        var passed = true
        fun expect(step: String, actual: CasResult, expected: CasResult) {
            val ok = actual == expected
            passed = passed && ok
            log("${if (ok) "PASS" else "FAIL"} $step: ${describe(actual)} (expected ${describe(expected)})")
        }
        fun expectThat(step: String, ok: Boolean, detail: String) {
            passed = passed && ok
            log("${if (ok) "PASS" else "FAIL"} $step: $detail")
        }

        log("account: ${store.accountEmail()}")
        clear()
        try {
            expect("create (v3 multipart)", store.casHead(null, "v0".encodeToByteArray()), CasResult.Committed)
            val first = store.readHead() ?: return failed("no manifest after create")
            log("head: file=${first.fileId} etag=${first.etag}")

            expect("PUT fresh etag", store.casHead(first, "v1".encodeToByteArray()), CasResult.Committed)
            expect("PUT stale etag", store.casHead(first, "v2".encodeToByteArray()), CasResult.Conflict)

            val fresh = store.readHead() ?: return failed("manifest vanished")
            val content = fresh.bytes.decodeToString()
            expectThat("content after stale PUT", content == "v1", "\"$content\" (expected \"v1\")")

            val results = coroutineScope {
                (1..RACERS).map { i -> async { store.casHead(fresh, "race $i".encodeToByteArray()) } }.awaitAll()
            }
            results.forEachIndexed { i, result -> log("race ${i + 1}: ${describe(result)}") }
            val committed = results.count { it == CasResult.Committed }
            val conflicts = results.count { it == CasResult.Conflict }
            expectThat(
                "race of $RACERS",
                committed == 1 && conflicts == RACERS - 1,
                "$committed committed, $conflicts conflict (expected 1 and ${RACERS - 1})",
            )
            if (committed == 1) {
                val winner = "race ${results.indexOf(CasResult.Committed) + 1}"
                val head = store.readHead()?.bytes?.decodeToString()
                expectThat("head after race", head == winner, "\"$head\" (expected \"$winner\")")
            }
        } finally {
            withContext(NonCancellable) { clear() }
        }
        return passed
    }

    private fun failed(reason: String): Boolean {
        log("FAIL $reason")
        return false
    }

    private suspend fun clear() {
        store.list().forEach { store.delete(it.id) }
    }

    private fun describe(result: CasResult) = when (result) {
        CasResult.Committed -> "200 Committed"
        CasResult.Conflict -> "412 Conflict"
        is CasResult.Ambiguous -> "Ambiguous (${result.cause})"
    }

    companion object {
        const val RACERS = 8
    }
}
