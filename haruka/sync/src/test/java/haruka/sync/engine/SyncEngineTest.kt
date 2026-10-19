package haruka.sync.engine

import haruka.sync.crypto.SyncKey
import haruka.sync.model.FORMAT_VERSION
import haruka.sync.model.ManifestBody
import haruka.sync.remote.FakeRemoteStore
import haruka.sync.remote.ManifestFile
import haruka.sync.remote.RemoteFile
import haruka.sync.remote.SyncError
import haruka.sync.store.RemovalOrigin
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/** Commit protocol scenarios on two or three simulated devices sharing a [FakeRemoteStore] backend. */
class SyncEngineTest {

    @TempDir
    lateinit var root: Path

    private val reading = "Reading"
    private val done = "Done"

    /** Device a with [n] favorites (4 chapters each) in two categories and one pref, b joined, both settled. */
    private suspend fun twoDevices(n: Int = 20): Triple<SimWorld, SimDevice, SimDevice> {
        val world = SimWorld(root)
        val a = world.device("a")
        val b = world.device("b")
        val readingId = a.library.createCategory(reading)
        val doneId = a.library.createCategory(done)
        for (i in 1..n) a.library.addEntry(key(i), 4, world.nowMillis, if (i % 2 == 0) readingId else doneId)
        a.library.prefs["haruka_compact_titles"] = "true"
        world.settle()
        return Triple(world, a, b)
    }

    private fun chapter(n: Int, c: Int) = "/manga/$n/c$c"

    private fun SimDevice.chapterOf(n: Int, c: Int) =
        library.entries.getValue(key(n)).chapters.first { it.url == chapter(n, c) }

    private fun headOf(world: SimWorld) = ManifestFile.decode(world.manifest().bytes)

    private fun bodyOf(world: SimWorld): ManifestBody = headOf(world).decodePlaintext().body

    private fun ManifestBody.fileIds() =
        buckets.map { it.payload.fileId }.toSet() + listOfNotNull(categoriesRef?.fileId, prefsRef?.fileId)

    private fun snapshot(world: SimWorld) = world.backend.files().map { Triple(it.id, it.generation, it.name) }

    @Test
    fun `a joining device receives the library and needs no commit`() = runBlocking<Unit> {
        val (world, a, b) = twoDevices()

        b.library.projection() shouldBe a.library.projection()
        b.library.favorites() shouldHaveSize 20
        b.library.categories.values.map { it.name } shouldBe listOf(reading, done)
        headOf(world).header.commitSeq shouldBe 1
    }

    @Test
    fun `edits on both devices merge three-way, including unread`() = runBlocking<Unit> {
        val (world, a, b) = twoDevices()
        a.library.read(key(3), chapter(3, 3), world.nowMillis)
        world.settle()

        a.library.read(key(1), chapter(1, 1), world.nowMillis)
        b.library.read(key(1), chapter(1, 2), world.nowMillis)
        a.library.editChapter(key(3), chapter(3, 3)) { it.copy(read = false) }
        world.settle()

        for (device in listOf(a, b)) {
            device.chapterOf(1, 1).read shouldBe true
            device.chapterOf(1, 2).read shouldBe true
            device.chapterOf(3, 3).read shouldBe false
        }
        a.library.projection() shouldBe b.library.projection()
    }

    @Test
    fun `category rename and delete win over concurrent assignments`() = runBlocking<Unit> {
        val (world, a, b) = twoDevices()
        a.library.categories.computeIfPresent(a.library.categoryId(reading)) { _, c -> c.copy(name = "Now") }
        b.library.toggleCategory(key(1), b.library.categoryId(reading))
        a.library.deleteCategory(a.library.categoryId(done))
        b.library.toggleCategory(key(2), b.library.categoryId(done))
        world.settle()

        for (device in listOf(a, b)) {
            device.library.categories.values.map { it.name } shouldBe listOf("Now")
            val now = device.library.categoryId("Now").toString()
            device.library.entries.getValue(key(1)).categoryUuids shouldContain now
            device.library.entries.getValue(key(2)).categoryUuids shouldBe listOf(now)
        }
        a.library.projection() shouldBe b.library.projection()
    }

    @Test
    fun `a sync without changes downloads nothing and skips the commit`() = runBlocking<Unit> {
        val (world, a, _) = twoDevices()
        val before = snapshot(world)

        val outcome = a.synced()

        outcome.committed shouldBe false
        outcome.downloadedFiles shouldBe 0
        outcome.uploadedFiles shouldBe 0
        snapshot(world) shouldBe before
    }

    @Test
    fun `sync never inserts chapter rows into an existing entry`() = runBlocking<Unit> {
        val (world, a, b) = twoDevices()
        a.library.edit(key(1)) { it.copy(chapters = it.chapters + it.chapters[0].copy(url = "/manga/1/c9")) }
        a.library.read(key(1), "/manga/1/c9", world.nowMillis)
        world.settle()

        b.library.entries.getValue(key(1)).chapters.map { it.url } shouldBe (1..4).map { chapter(1, it) }
        bodyOf(world).buckets.size shouldNotBe 0
    }

    @Test
    fun `a lost CAS race restarts from the new head and loses no edit`() = runBlocking<Unit> {
        val (world, a, b) = twoDevices()
        a.library.read(key(1), chapter(1, 1), world.nowMillis)
        b.library.read(key(2), chapter(2, 1), world.nowMillis)
        var raced = false
        world.backend.intercept = { step, _ ->
            if (step == FakeRemoteStore.Step.CAS && !raced) {
                raced = true
                b.synced()
            }
        }

        val outcome = a.synced()
        world.backend.intercept = { _, _ -> }
        world.settle()

        outcome.committed shouldBe true
        a.logs shouldContain "HarukaSync restart reason=cas"
        for (device in listOf(a, b)) {
            device.chapterOf(1, 1).read shouldBe true
            device.chapterOf(2, 1).read shouldBe true
        }
        a.library.projection() shouldBe b.library.projection()
    }

    @Test
    fun `an ambiguous CAS that landed is recognised by its commit id`() = runBlocking<Unit> {
        val (world, a, b) = twoDevices()
        val seq = headOf(world).header.commitSeq
        a.library.read(key(1), chapter(1, 1), world.nowMillis)
        world.backend.nextCasAmbiguous = true

        val outcome = a.synced()

        outcome.committed shouldBe true
        outcome.commitSeq shouldBe seq + 1
        headOf(world).header.commitSeq shouldBe seq + 1
        a.store.readState().pendingCommit shouldBe null
        a.store.readState().lastOwnCommitSeq shouldBe seq + 1
        world.settle()
        b.chapterOf(1, 1).read shouldBe true
    }

    @Test
    fun `an ambiguous CAS that was lost is retried on the re-read head`() = runBlocking<Unit> {
        val (world, a, b) = twoDevices()
        val seq = headOf(world).header.commitSeq
        a.library.read(key(1), chapter(1, 1), world.nowMillis)
        world.backend.nextCasAmbiguous = false

        val outcome = a.synced()

        outcome.commitSeq shouldBe seq + 1
        headOf(world).header.commitSeq shouldBe seq + 1
        world.settle()
        b.chapterOf(1, 1).read shouldBe true
    }

    @Test
    fun `endless CAS conflicts end contended and clean up their uploads`() = runBlocking<Unit> {
        val (world, a, _) = twoDevices()
        val payloads = world.payloadIds()
        a.library.read(key(1), chapter(1, 1), world.nowMillis)
        world.backend.intercept = { step, id ->
            if (step == FakeRemoteStore.Step.CAS) world.backend.overwrite(id!!, world.backend.file(id)!!.bytes)
        }

        a.sync() shouldBe SyncOutcome.Contended(3)
        world.payloadIds() shouldBe payloads
        a.store.readState().lastResult shouldBe "Contended"

        world.backend.intercept = { _, _ -> }
        a.synced().committed shouldBe true
    }

    @Test
    fun `more than 16 foreign commits while offline merge in one sync`() = runBlocking<Unit> {
        val (world, a, b) = twoDevices()
        a.library.read(key(20), chapter(20, 4), world.nowMillis)
        for (i in 0..<20) {
            b.library.read(key(2 + i % 10), chapter(2 + i % 10, 1 + i / 10), world.nowMillis)
            b.synced().committed shouldBe true
        }

        a.synced().committed shouldBe true
        world.settle()

        for (i in 0..<20) a.chapterOf(2 + i % 10, 1 + i / 10).read shouldBe true
        b.chapterOf(20, 4).read shouldBe true
        a.library.projection() shouldBe b.library.projection()
    }

    @Test
    fun `a wiped remote is rebuilt by union merges that remove nothing`() = runBlocking<Unit> {
        val (world, a, b) = twoDevices()
        world.backend.files().forEach { world.backend.remove(it.id) }
        b.library.setFavorite(key(1), false, world.nowMillis)

        b.synced().union shouldBe true
        a.synced().union shouldBe true
        world.settle()

        val all = (1..20).map(::key).toSet()
        a.library.favorites() shouldBe all
        b.library.favorites() shouldBe all
        a.library.projection() shouldBe b.library.projection()
    }

    @Test
    fun `a rolled-back remote stops without writes until the user uploads this library`() = runBlocking<Unit> {
        val (world, a, b) = twoDevices()
        val manifest = world.manifest()
        val oldBytes = manifest.bytes.copyOf()
        val oldSeq = headOf(world).header.commitSeq
        a.library.read(key(1), chapter(1, 1), world.nowMillis)
        a.synced()
        world.backend.overwrite(manifest.id, oldBytes)
        val remoteBefore = snapshot(world)
        val localBefore = a.library.projection()

        a.sync() shouldBe SyncOutcome.Stopped(SyncError.RemoteRolledBack(foundSeq = oldSeq, expectedSeq = oldSeq + 1))
        snapshot(world) shouldBe remoteBefore
        a.library.projection() shouldBe localBefore

        val oldEpoch = headOf(world).header.remoteEpoch
        val recovery = a.sync(newEpoch = true)
        recovery.shouldBeInstanceOf<SyncOutcome.Synced>()
        recovery.union shouldBe true
        recovery.committed shouldBe true
        headOf(world).header.remoteEpoch shouldNotBe oldEpoch
        world.settle()
        b.chapterOf(1, 1).read shouldBe true
        a.library.projection() shouldBe b.library.projection()
    }

    @Test
    fun `a mass local removal is held until confirmed, then held remotely until confirmed there`() =
        runBlocking<Unit> {
            val (world, a, b) = twoDevices(40)
            val removed = (1..30).map(::key).toSet()
            removed.forEach { a.library.setFavorite(it, false, world.nowMillis) }

            val held = a.synced()
            held.pendingRemovals.map { it.key }.toSet() shouldBe removed
            held.pendingRemovals.map { it.origin }.toSet() shouldBe setOf(RemovalOrigin.LOCAL)
            b.synced()
            b.library.favorites() shouldHaveSize 40
            a.synced().pendingRemovals shouldHaveSize 30
            a.store.readState().pendingRemovals shouldHaveSize 30

            a.synced(confirm = removed).pendingRemovals shouldBe emptyList()
            val remote = b.synced()
            remote.pendingRemovals.map { it.origin }.toSet() shouldBe setOf(RemovalOrigin.REMOTE)
            remote.pendingRemovals shouldHaveSize 30
            b.library.favorites() shouldHaveSize 40
            // The base kept the pre-merge entries, so the guard fires again.
            b.synced().pendingRemovals shouldHaveSize 30

            b.synced(confirm = removed).pendingRemovals shouldBe emptyList()
            b.library.favorites() shouldBe (31..40).map(::key).toSet()
            world.settle()
            a.library.projection() shouldBe b.library.projection()
        }

    @Test
    fun `rejecting a held local removal re-favorites the entries here`() = runBlocking<Unit> {
        val (world, a, b) = twoDevices(40)
        val removed = (1..30).map(::key).toSet()
        removed.forEach { a.library.setFavorite(it, false, world.nowMillis) }
        a.synced().pendingRemovals shouldHaveSize 30

        a.synced(reject = removed).pendingRemovals shouldBe emptyList()
        world.settle()

        a.library.favorites() shouldHaveSize 40
        b.library.favorites() shouldHaveSize 40
        a.store.readState().pendingRemovals shouldBe emptyList()
    }

    @Test
    fun `rejecting a held remote removal re-adds the entries everywhere`() = runBlocking<Unit> {
        val (world, a, b) = twoDevices(40)
        val removed = (1..30).map(::key).toSet()
        removed.forEach { a.library.setFavorite(it, false, world.nowMillis) }
        a.synced(confirm = removed)
        b.synced().pendingRemovals shouldHaveSize 30

        b.synced(reject = removed).pendingRemovals shouldBe emptyList()
        world.settle()

        a.library.favorites() shouldHaveSize 40
        b.library.favorites() shouldHaveSize 40
        a.library.projection() shouldBe b.library.projection()
    }

    @Test
    fun `an encrypted remote needs the passphrase and rejects a wrong one`() = runBlocking<Unit> {
        val world = SimWorld(root)
        val a = world.device("a")
        val b = world.device("b")
        val key = SyncKey.create("correct horse", 1_000)
        a.key = key
        for (i in 1..3) a.library.addEntry(key(i), 2, world.nowMillis)
        a.synced()

        world.backend.files().filter {
            it.name != LAYOUT.manifestName
        }.forEach { it.name.endsWith(".enc") shouldBe true }
        b.sync() shouldBe SyncOutcome.Stopped(SyncError.NeedsPassphrase(key.keyId))
        b.engine.unlock("wrong horse") shouldBe UnlockResult.Failed(SyncError.WrongPassphrase(key.keyId))
        val unlocked = b.engine.unlock("correct horse")
        unlocked.shouldBeInstanceOf<UnlockResult.Unlocked>()
        b.key = unlocked.key
        world.settle()

        b.library.projection() shouldBe a.library.projection()
    }

    @Test
    fun `a device holding a key refuses a plaintext remote`() = runBlocking<Unit> {
        val (world, _, _) = twoDevices()
        val c = world.device("c")
        c.key = SyncKey.create("pass", 1_000)

        c.sync() shouldBe SyncOutcome.Stopped(SyncError.RemotePlaintext)
        c.engine.unlock("pass") shouldBe UnlockResult.NotEncrypted
    }

    @Test
    fun `a corrupt payload stops the sync`() = runBlocking<Unit> {
        val (world, _, _) = twoDevices()
        val payload = world.backend.files().first { it.name != LAYOUT.manifestName }
        world.backend.overwrite(payload.id, payload.bytes.copyOf().also { it[it.size - 1] = (it.last() + 1).toByte() })
        val c = world.device("c")

        c.sync().shouldBeInstanceOf<SyncOutcome.Stopped>().error.shouldBeInstanceOf<SyncError.Corrupt>()
        c.library.entries.isEmpty() shouldBe true
    }

    @Test
    fun `a newer format stops before anything is written`() = runBlocking<Unit> {
        val (world, a, _) = twoDevices()
        val head = headOf(world)
        val newer = ManifestFile(head.header.copy(formatVersion = FORMAT_VERSION + 1), head.storedBody)
        world.backend.overwrite(world.manifest().id, newer.encode())
        a.library.read(key(1), chapter(1, 1), world.nowMillis)
        val before = snapshot(world)

        a.sync() shouldBe SyncOutcome.Stopped(SyncError.FormatTooNew(FORMAT_VERSION + 1))
        snapshot(world) shouldBe before
    }

    @Test
    fun `garbage collection keeps exactly the head's payloads and fresh orphans`() = runBlocking<Unit> {
        val (world, a, b) = twoDevices()
        for (i in 1..3) {
            a.library.read(key(i), chapter(i, 1), world.nowMillis)
            a.synced()
            b.synced()
        }
        world.payloadIds() shouldBe bodyOf(world).fileIds()

        val hour = 3_600_000L
        val bucket = RemoteFile.Bucket(1, "0123456789abcdef", encrypted = false)
        val props = LAYOUT.appProperties(bucket)
        val duplicate = world.backend.add(
            LAYOUT.manifestName,
            LAYOUT.appProperties(RemoteFile.Manifest),
            world.manifest().bytes,
            createdAtMillis = 50 * hour,
        )
        val old = world.backend.add(LAYOUT.nameOf(bucket), props, byteArrayOf(1), createdAtMillis = 1)
        val young = world.backend.add(
            LAYOUT.nameOf(bucket),
            props,
            byteArrayOf(2),
            createdAtMillis = 50 * hour - 60_000,
        )

        a.synced()

        val ids = world.backend.files().map { it.id }.toSet()
        (duplicate in ids) shouldBe false
        (old in ids) shouldBe false
        (young in ids) shouldBe true
        world.payloadIds() - young shouldBe bodyOf(world).fileIds()
    }

    @Test
    fun `an entry the applier skipped is merged again on the next sync`() = runBlocking<Unit> {
        val (world, a, b) = twoDevices()
        b.library.edit(key(1)) { it.copy(notes = "from b") }
        b.synced()

        a.library.skip = setOf(key(1))
        a.synced()
        a.library.entries.getValue(key(1)).notes shouldBe ""

        a.library.skip = emptySet()
        a.synced().downloadedFiles shouldBe 1
        a.library.entries.getValue(key(1)).notes shouldBe "from b"
        world.settle()
        a.library.projection() shouldBe b.library.projection()
    }

    @Test
    fun `an unrealized chapter write is applied again on the next sync`() = runBlocking<Unit> {
        val (world, a, b) = twoDevices()
        b.library.read(key(1), chapter(1, 1), world.nowMillis)
        b.synced()

        a.library.unrealize = setOf(key(1))
        a.synced()
        a.chapterOf(1, 1).read shouldBe false

        a.library.unrealize = emptySet()
        a.synced()
        a.chapterOf(1, 1).read shouldBe true
        world.settle()
        a.library.projection() shouldBe b.library.projection()
    }

    @Test
    fun `three devices converge on concurrent edits`() = runBlocking<Unit> {
        val (world, a, b) = twoDevices()
        val c = world.device("c")
        world.settle()
        a.library.read(key(1), chapter(1, 1), world.nowMillis)
        b.library.edit(key(1)) { it.copy(notes = "b") }
        c.library.addEntry(key(99), 3, world.nowMillis, c.library.categoryId(reading))
        c.library.prefs["haruka_compact_titles"] = "false"
        world.settle()

        val expected = a.library.projection()
        b.library.projection() shouldBe expected
        c.library.projection() shouldBe expected
        a.library.favorites() shouldContain key(99)
        a.library.prefs["haruka_compact_titles"] shouldBe "false"
    }
}
