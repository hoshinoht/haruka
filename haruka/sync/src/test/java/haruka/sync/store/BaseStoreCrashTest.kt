package haruka.sync.store

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Simulates a process death before every file-system mutation (each temp write, rename, mkdir and delete) by
 * throwing from the store's crash hook and abandoning the store, then reopens the directory and checks that it
 * holds exactly one complete generation: the old one up to the `CURRENT` rename, the new one after it.
 */
class BaseStoreCrashTest {

    @TempDir
    lateinit var root: Path

    private class Crash : RuntimeException()

    private var runs = 0

    /** A fresh store directory, optionally seeded from [template]. */
    private fun freshDirectory(template: Path?): Path {
        val dir = root.resolve("run-${runs++}")
        if (template != null) template.copyTreeTo(dir) else Files.createDirectory(dir)
        return dir
    }

    /** The crash points [operation] passes through on a store opened over a copy of [template]. */
    private fun crashPoints(template: Path?, operation: (BaseStore) -> Unit): List<String> {
        val points = mutableListOf<String>()
        operation(open(freshDirectory(template)) { points += it })
        return points
    }

    /** Runs [operation] over a copy of [template], dying at crash point [target]; returns the directory. */
    private fun crashAt(template: Path?, target: Int, operation: (BaseStore) -> Unit): Path {
        val dir = freshDirectory(template)
        var calls = 0
        val store = open(dir) { if (calls++ == target) throw Crash() }
        shouldThrow<Crash> { operation(store) }
        return dir
    }

    private fun reopen(dir: Path) = open(dir) {}

    // Without fsync: the simulated crash is a process death, which keeps written data, and fsync per file would
    // make the ~800 runs here take minutes.
    private fun open(dir: Path, crashPoint: (String) -> Unit) = BaseStore.open(dir, crashPoint, fsync = false)

    /** A store directory holding the active generation "one" (seq 1) and a state naming it. */
    private fun baseline(): Path {
        val dir = freshDirectory(null)
        val store = reopen(dir)
        store.activate(store.stageFull(seq = 1, tag = "one"))
        store.writeState(SyncState(baseCommitSeq = 1))
        return dir
    }

    private val commitTwo: (BaseStore) -> Unit = { it.activate(it.stageFull(seq = 2, tag = "two")) }

    @Test
    fun `crash at every step of a commit leaves exactly one complete base`() {
        val template = baseline()
        val points = crashPoints(template, commitTwo)
        val swap = points.indexOf("move CURRENT")
        points shouldContain "move CURRENT"

        for (target in points.indices) {
            withClue("crash before ${points[target]}") {
                val dir = crashAt(template, target, commitTwo)
                val store = reopen(dir)
                val (seq, tag) = if (target <= swap) 1L to "one" else 2L to "two"
                val active = store.current!!

                active.contents() shouldBe expectedContents(seq, tag)
                dir.childNames() shouldBe setOf("CURRENT", "state.json", active.name)
                store.readState() shouldBe SyncState(baseCommitSeq = 1)
            }
        }
        println("BaseStoreCrashTest commit: ${points.size} crash points")
    }

    @Test
    fun `crash at any step of the first commit leaves no base`() {
        val points = crashPoints(null, commitTwo)
        // With no previous base there is nothing to delete after the swap, so the rename is the last step.
        points.last() shouldBe "move CURRENT"

        for (target in points.indices) {
            withClue("crash before ${points[target]}") {
                val dir = crashAt(null, target, commitTwo)

                reopen(dir).current shouldBe null
                dir.childNames() shouldBe emptySet()
            }
        }
    }

    @Test
    fun `crash at any step of a state write keeps the old state`() {
        val template = baseline()
        val next = SyncState(baseCommitSeq = 2, lastOwnCommitSeq = 2, lastResult = "Success")
        val write: (BaseStore) -> Unit = { it.writeState(next) }
        val points = crashPoints(template, write)
        points shouldBe listOf("write state.json", "move state.json")

        for (target in points.indices) {
            withClue("crash before ${points[target]}") {
                val dir = crashAt(template, target, write)
                val store = reopen(dir)

                store.readState() shouldBe SyncState(baseCommitSeq = 1)
                dir.childNames() shouldBe setOf("CURRENT", "state.json", "base-1-0")
            }
        }
    }

    @Test
    fun `crash at every step of recovery still recovers the old base`() {
        val template = baseline()
        // The messiest pre-swap state: a fully staged generation and a written but unrenamed CURRENT.tmp.
        val interrupted = crashAt(template, crashPoints(template, commitTwo).indexOf("move CURRENT"), commitTwo)
        val recoveryPoints = crashPoints(interrupted) {}
        recoveryPoints.size shouldBe 1 + 132 + 1 // CURRENT.tmp, the staged files, the staged directory

        for (target in recoveryPoints.indices) {
            withClue("recovery crash before ${recoveryPoints[target]}") {
                val dir = freshDirectory(interrupted)
                var calls = 0
                shouldThrow<Crash> { open(dir) { if (calls++ == target) throw Crash() } }
                val store = reopen(dir)

                store.current!!.contents() shouldBe expectedContents(1, "one")
                dir.childNames() shouldBe setOf("CURRENT", "state.json", "base-1-0")
            }
        }
    }
}
