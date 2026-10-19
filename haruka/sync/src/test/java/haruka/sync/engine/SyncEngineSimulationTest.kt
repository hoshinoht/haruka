package haruka.sync.engine

import haruka.sync.model.EntryKey
import haruka.sync.remote.FakeRemoteStore
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.random.Random

/**
 * Stateful simulation (plan "Test properties", stateful p3/commit): three devices make random edits and sync at
 * random, with crashes at protocol boundaries and mid-apply, CAS races and ambiguous CAS outcomes injected. After
 * quiescence every device holds the same library; with monotone edits only, no edit is lost.
 */
class SyncEngineSimulationTest {

    @TempDir
    lateinit var root: Path

    @Test
    fun `devices converge under random edits, races, crashes and ambiguous commits`() = runBlocking<Unit> {
        for (seed in SEEDS) simulate(seed, monotone = false)
    }

    @Test
    fun `acknowledged monotone edits are never lost`() = runBlocking<Unit> {
        for (seed in SEEDS) simulate(seed, monotone = true)
    }

    private class Run(val world: SimWorld, val random: Random, val monotone: Boolean) {
        val reads = HashSet<Pair<EntryKey, String>>()
        val bookmarks = HashSet<Pair<EntryKey, String>>()
        val memberships = HashSet<Pair<EntryKey, String>>()
        val added = HashSet<EntryKey>()
        var nextKey = 1000
        var removalsLeft = 3
        var nextCategory = 0
        var crashes = 0
    }

    private suspend fun simulate(seed: Long, monotone: Boolean) {
        println("SyncEngineSimulationTest seed=$seed monotone=$monotone")
        val world = SimWorld(root.resolve("seed-$seed-$monotone"))
        val devices = listOf(world.device("a"), world.device("b"), world.device("c"))
        val a = devices[0]
        val first = a.library.createCategory("C1")
        val second = a.library.createCategory("C2")
        for (i in 1..40) a.library.addEntry(key(i), 4, world.nowMillis, if (i % 2 == 0) first else second)
        world.settle()

        val run = Run(world, Random(seed), monotone)
        withClue("seed=$seed monotone=$monotone") {
            repeat(STEPS) {
                val device = devices.random(run.random)
                edit(run, device)
                if (run.random.nextInt(3) == 0) syncWithFaults(run, device, devices)
            }
            world.settle(rounds = 8)

            val expected = a.library.projection()
            for (device in devices) device.library.projection() shouldBe expected
            if (monotone) checkMonotone(run, devices)
            println("SyncEngineSimulationTest seed=$seed crashes=${run.crashes} head=${world.manifest().generation}")
        }
    }

    private fun edit(run: Run, device: SimDevice) {
        val library = device.library
        val random = run.random
        val favorites = library.favorites().sorted()
        val entry = favorites.random(random)
        val url = library.entries.getValue(entry).chapters.random(random).url
        val now = run.world.nowMillis++
        when (random.nextInt(10)) {
            0, 1, 2 -> {
                library.read(entry, url, now)
                run.reads += entry to url
            }
            3 -> {
                library.editChapter(entry, url) { it.copy(bookmark = true) }
                run.bookmarks += entry to url
            }
            4 -> if (run.monotone) {
                library.editChapter(entry, url) { it.copy(lastPageRead = it.lastPageRead + 1) }
            } else {
                library.editChapter(entry, url) { it.copy(read = false, bookmark = false) }
            }
            5 -> {
                val category = library.categories.entries.toList().random(random)
                if (run.monotone) {
                    if (category.key.toString() !in library.entries.getValue(entry).categoryUuids) {
                        library.toggleCategory(entry, category.key)
                    }
                    run.memberships += entry to category.value.name
                } else {
                    library.toggleCategory(entry, category.key)
                }
            }
            6 -> {
                val key = EntryKey(200, "/new/${device.name}/${run.nextKey++}")
                val ids = library.categories.keys.toList()
                library.addEntry(key, 3, now, *ids.take(1).toLongArray())
                run.added += key
            }
            7 -> if (run.monotone) {
                library.edit(entry) { it.copy(notes = it.notes) }
            } else if (run.removalsLeft > 0) {
                run.removalsLeft--
                library.setFavorite(entry, false, now)
            } else {
                library.entries.values.firstOrNull { !it.favorite }?.let { library.setFavorite(it.key, true, now) }
            }
            8 -> if (run.monotone) {
                library.prefs["haruka_compact_titles"] = "true"
            } else {
                when (random.nextInt(3)) {
                    0 -> library.createCategory("${device.name}-${run.nextCategory++}")
                    1 -> {
                        val (id, category) = library.categories.entries.toList().random(random)
                        library.categories[id] = category.copy(name = "${category.name}'")
                    }
                    else -> if (library.categories.size > 1) {
                        library.deleteCategory(library.categories.keys.toList().random(random))
                    }
                }
            }
            else -> library.edit(entry) { it.copy(viewerFlags = random.nextLong(4)) }
        }
    }

    private suspend fun syncWithFaults(run: Run, device: SimDevice, devices: List<SimDevice>) {
        val world = run.world
        val random = run.random
        when (random.nextInt(6)) {
            0 -> {
                val point = SyncCheckpoint.entries.random(random)
                device.engine.checkpoint = { if (it == point) throw Crash() }
            }
            1 -> device.library.crashOnApply(1 + random.nextInt(2))
            2 -> world.backend.nextCasAmbiguous = random.nextBoolean()
            3 -> {
                val other = devices.filter { it !== device }.random(random)
                var raced = false
                world.backend.intercept = { step, _ ->
                    if (step == FakeRemoteStore.Step.CAS && !raced) {
                        raced = true
                        other.synced()
                    }
                }
            }
        }
        try {
            val outcome = device.sync()
            check(outcome is SyncOutcome.Synced) { "${device.name}: $outcome" }
        } catch (e: Crash) {
            run.crashes++
            device.restart()
        } finally {
            device.engine.checkpoint = {}
            device.library.crashOnApply(null)
            world.backend.nextCasAmbiguous = null
            world.backend.intercept = { _, _ -> }
        }
    }

    private fun checkMonotone(run: Run, devices: List<SimDevice>) {
        for (device in devices) {
            val library = device.library
            for ((key, url) in run.reads) {
                withClue("${device.name} read $key $url") {
                    library.entries.getValue(key).chapters.first { it.url == url }.read shouldBe true
                }
            }
            for ((key, url) in run.bookmarks) {
                withClue("${device.name} bookmark $key $url") {
                    library.entries.getValue(key).chapters.first { it.url == url }.bookmark shouldBe true
                }
            }
            for ((key, name) in run.memberships) {
                withClue("${device.name} membership $key $name") {
                    val id = library.categoryId(name).toString()
                    (id in library.entries.getValue(key).categoryUuids) shouldBe true
                }
            }
            for (key in run.added) library.entries.getValue(key).favorite shouldBe true
        }
    }

    private companion object {
        val SEEDS = 1L..6L
        const val STEPS = 40
    }
}
