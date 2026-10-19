package haruka.sync.engine

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Kills device a at every protocol step boundary (after upload, after CAS, mid-apply, after apply, after staging,
 * after activation) and before every non-bucket base-store file operation of its sync, restarts it, and checks
 * that every device converges with no edit lost.
 */
class SyncEngineCrashTest {

    @TempDir
    lateinit var root: Path

    private var runs = 0

    /**
     * a and b share 12 entries; a reads one chapter, b reads one chapter in each of four entries and commits,
     * so a's sync both commits and applies remote changes in several buckets.
     */
    private suspend fun scenario(): Triple<SimWorld, SimDevice, SimDevice> {
        val world = SimWorld(root.resolve("run-${runs++}"))
        val a = world.device("a")
        val b = world.device("b")
        for (i in 1..12) a.library.addEntry(key(i), 3, world.nowMillis)
        world.settle()
        a.library.read(key(1), "/manga/1/c1", world.nowMillis)
        for (i in 2..5) b.library.read(key(i), "/manga/$i/c2", world.nowMillis)
        b.synced()
        return Triple(world, a, b)
    }

    private suspend fun crashAndRecover(inject: (SimDevice) -> Unit) {
        val (world, a, b) = scenario()
        inject(a)
        shouldThrow<Crash> { a.sync() }
        a.storeCrash = {}
        a.library.crashOnApply(null)
        a.restart()

        world.settle()

        for (device in listOf(a, b)) {
            device.library.entries.getValue(key(1)).chapters[0].read shouldBe true
            for (i in 2..5) device.library.entries.getValue(key(i)).chapters[1].read shouldBe true
        }
        a.library.projection() shouldBe b.library.projection()
        a.store.readState().pendingCommit shouldBe null
    }

    @Test
    fun `crash at every protocol step boundary`() = runBlocking<Unit> {
        for (point in SyncCheckpoint.entries) {
            withClue("crash at $point") {
                crashAndRecover { device -> device.engine.checkpoint = { if (it == point) throw Crash() } }
            }
        }
    }

    @Test
    fun `crash in the middle of the apply`() = runBlocking<Unit> {
        for (call in 1..2) {
            withClue("crash in applyEntries call $call") {
                crashAndRecover { it.library.crashOnApply(call) }
            }
        }
    }

    @Test
    fun `crash before base store file operations`() = runBlocking<Unit> {
        val points = ArrayList<String>()
        run {
            val (_, a, _) = scenario()
            a.storeCrash = { points += it }
            a.synced()
        }
        // Bucket files repeat the same step 128 times; keep the first and last of each kind.
        val bucket = Regex("b\\d{3}\\.pb\\.gz")
        val selected = points.indices.filter { i ->
            val point = points[i]
            !bucket.containsMatchIn(point) ||
                points.indexOfFirst { it.replace(bucket, "") == point.replace(bucket, "") } == i ||
                points.indexOfLast { it.replace(bucket, "") == point.replace(bucket, "") } == i
        }
        println("SyncEngineCrashTest store points=${points.size} selected=${selected.size}")
        for (target in selected) {
            withClue("crash before ${points[target]}") {
                crashAndRecover { device ->
                    var calls = 0
                    device.storeCrash = { if (calls++ == target) throw Crash() }
                }
            }
        }
    }
}
