package haruka.sync.engine

import haruka.sync.crypto.SyncKey
import haruka.sync.model.ManifestBody
import haruka.sync.remote.FakeRemoteStore
import haruka.sync.remote.ManifestFile
import haruka.sync.remote.SyncError
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/** The rewrite commit that enables, changes or disables encryption on a remote that already holds a library. */
class SyncEngineReencryptTest {

    @TempDir
    lateinit var root: Path

    /** Device a with 20 favorites and a pref, b joined, both settled on a plaintext remote. */
    private suspend fun twoDevices(): Triple<SimWorld, SimDevice, SimDevice> {
        val world = SimWorld(root)
        val a = world.device("a")
        val b = world.device("b")
        val reading = a.library.createCategory("Reading")
        for (i in 1..20) a.library.addEntry(key(i), 4, world.nowMillis, reading)
        a.library.prefs["haruka_compact_titles"] = "true"
        world.settle()
        return Triple(world, a, b)
    }

    private fun chapter(n: Int, c: Int) = "/manga/$n/c$c"

    private fun headOf(world: SimWorld) = ManifestFile.decode(world.manifest().bytes)

    /** The head's body, opened with [key] when the head is encrypted. */
    private fun bodyOf(world: SimWorld, key: SyncKey?): ManifestBody {
        val file = headOf(world)
        return if (key == null) file.decodePlaintext().body else key.decryptManifest(LAYOUT, file).body
    }

    private fun ManifestBody.fileIds() =
        buckets.map { it.payload.fileId }.toSet() + listOfNotNull(categoriesRef?.fileId, prefsRef?.fileId)

    private fun payloadNames(world: SimWorld) =
        world.backend.files().filter { it.name != LAYOUT.manifestName }.map { it.name }

    private fun snapshot(world: SimWorld) = world.backend.files().map { Triple(it.id, it.generation, it.name) }

    private fun newKey(passphrase: String) = SyncKey.create(passphrase, 1_000)

    @Test
    fun `enabling encryption rewrites every file of an existing plaintext remote under the same epoch`() =
        runBlocking<Unit> {
            val (world, a, b) = twoDevices()
            val before = headOf(world).header
            val plainBody = bodyOf(world, null)
            val key = newKey("correct horse")

            val outcome = a.engine.reencrypt(current = null, next = key)

            outcome shouldBe RewriteOutcome.Rewritten(before.commitSeq + 1, plainBody.fileIds().size)
            val header = headOf(world).header
            header.remoteEpoch shouldBe before.remoteEpoch
            header.encryption shouldBe key.params
            val body = bodyOf(world, key)
            body.buckets.map { it.index to it.payload.plaintextSha } shouldBe
                plainBody.buckets.map { it.index to it.payload.plaintextSha }
            body.devices.single { it.id == "a" }.lastCommitSeq shouldBe before.commitSeq + 1
            body.prefsRef!!.plaintextSha shouldBe plainBody.prefsRef!!.plaintextSha
            // The plaintext copies are gone; only the encrypted head's payloads remain.
            world.payloadIds() shouldBe body.fileIds()
            payloadNames(world).forEach { it.endsWith(".enc") shouldBe true }

            // The writer's base stays valid: nothing to download, nothing to commit.
            a.key = key
            val next = a.synced()
            next.committed shouldBe false
            next.union shouldBe false
            next.downloadedFiles shouldBe 0

            val untouched = snapshot(world)
            b.sync() shouldBe SyncOutcome.Stopped(SyncError.NeedsPassphrase(key.keyId))
            snapshot(world) shouldBe untouched
        }

    @Test
    fun `a second device needs the new passphrase, then merges three-way and converges`() = runBlocking<Unit> {
        val (world, a, b) = twoDevices()
        a.library.setFavorite(key(1), false, world.nowMillis)
        a.synced()
        b.library.read(key(2), chapter(2, 1), world.nowMillis)
        val key = newKey("correct horse")
        a.engine.reencrypt(current = null, next = key).shouldBeInstanceOf<RewriteOutcome.Rewritten>()
        a.key = key

        val untouched = snapshot(world)
        b.sync() shouldBe SyncOutcome.Stopped(SyncError.NeedsPassphrase(key.keyId))
        snapshot(world) shouldBe untouched
        b.engine.unlock("wrong horse") shouldBe UnlockResult.Failed(SyncError.WrongPassphrase(key.keyId))
        b.key = b.engine.unlock("correct horse").shouldBeInstanceOf<UnlockResult.Unlocked>().key

        val joined = b.synced()
        joined.union shouldBe false
        joined.committed shouldBe true
        world.settle()

        b.library.projection() shouldBe a.library.projection()
        // Three-way, not union: the removal made before the rewrite is not resurrected.
        b.library.favorites() shouldNotContain key(1)
        a.library.entries.getValue(key(2)).chapters.first { it.url == chapter(2, 1) }.read shouldBe true
        payloadNames(world).forEach { it.endsWith(".enc") shouldBe true }
    }

    @Test
    fun `changing the passphrase moves the remote to a new key and locks out the old one`() = runBlocking<Unit> {
        val (world, a, b) = twoDevices()
        val old = newKey("old pass")
        a.engine.reencrypt(current = null, next = old).shouldBeInstanceOf<RewriteOutcome.Rewritten>()
        a.key = old
        b.key = b.engine.unlock("old pass").shouldBeInstanceOf<UnlockResult.Unlocked>().key
        world.settle()
        val epoch = headOf(world).header.remoteEpoch
        val oldPayloads = world.payloadIds()

        val new = newKey("new pass")
        a.engine.reencrypt(current = old, next = new).shouldBeInstanceOf<RewriteOutcome.Rewritten>()
        a.key = new

        headOf(world).header.remoteEpoch shouldBe epoch
        headOf(world).header.encryption shouldBe new.params
        world.payloadIds().intersect(oldPayloads) shouldBe emptySet()
        world.payloadIds() shouldBe bodyOf(world, new).fileIds()
        b.sync() shouldBe SyncOutcome.Stopped(SyncError.NeedsPassphrase(new.keyId))
        b.engine.unlock("old pass") shouldBe UnlockResult.Failed(SyncError.WrongPassphrase(new.keyId))
        b.key = b.engine.unlock("new pass").shouldBeInstanceOf<UnlockResult.Unlocked>().key
        b.synced().downloadedFiles shouldBe 0
        b.library.read(key(3), chapter(3, 2), world.nowMillis)
        world.settle()

        a.library.projection() shouldBe b.library.projection()
    }

    @Test
    fun `a device without the current key cannot rewrite the remote`() = runBlocking<Unit> {
        val (world, a, b) = twoDevices()
        val key = newKey("pass")
        a.engine.reencrypt(current = null, next = key).shouldBeInstanceOf<RewriteOutcome.Rewritten>()
        val untouched = snapshot(world)

        b.engine.reencrypt(current = null, next = newKey("other")) shouldBe
            RewriteOutcome.Stopped(SyncError.NeedsPassphrase(key.keyId))
        b.engine.reencrypt(current = null, next = null) shouldBe
            RewriteOutcome.Stopped(SyncError.NeedsPassphrase(key.keyId))
        snapshot(world) shouldBe untouched
    }

    @Test
    fun `disabling encryption rewrites the remote in plaintext and devices holding the key stop`() =
        runBlocking<Unit> {
            val (world, a, b) = twoDevices()
            val key = newKey("pass")
            a.engine.reencrypt(current = null, next = key).shouldBeInstanceOf<RewriteOutcome.Rewritten>()
            a.key = key
            b.key = b.engine.unlock("pass").shouldBeInstanceOf<UnlockResult.Unlocked>().key
            world.settle()
            val seq = headOf(world).header.commitSeq

            a.engine.reencrypt(current = key, next = null) shouldBe
                RewriteOutcome.Rewritten(seq + 1, world.payloadIds().size)
            a.key = null

            headOf(world).header.encryption shouldBe null
            val body = bodyOf(world, null)
            world.payloadIds() shouldBe body.fileIds()
            payloadNames(world).forEach { it.endsWith(".enc") shouldBe false }
            a.engine.reencrypt(current = null, next = null) shouldBe RewriteOutcome.Unchanged
            b.sync() shouldBe SyncOutcome.Stopped(SyncError.RemotePlaintext)

            b.key = null
            b.library.edit(key(4)) { it.copy(notes = "from b") }
            world.settle()
            a.library.projection() shouldBe b.library.projection()
            a.library.entries.getValue(key(4)).notes shouldBe "from b"
        }

    @Test
    fun `a commit landing during the rewrite makes it start over from the new head`() = runBlocking<Unit> {
        val (world, a, b) = twoDevices()
        b.library.read(key(5), chapter(5, 3), world.nowMillis)
        var raced = false
        world.backend.intercept = { step, _ ->
            if (step == FakeRemoteStore.Step.CAS && !raced) {
                raced = true
                b.synced().committed shouldBe true
            }
        }
        val key = newKey("pass")

        val outcome = a.engine.reencrypt(current = null, next = key)
        world.backend.intercept = { _, _ -> }

        val seq = headOf(world).header.commitSeq
        outcome shouldBe RewriteOutcome.Rewritten(seq, world.payloadIds().size)
        a.logs shouldContain "HarukaSync rewrite restart reason=cas"
        // The lost attempt's uploads are deleted; b's plaintext commit is rewritten, not dropped.
        world.payloadIds() shouldBe bodyOf(world, key).fileIds()
        payloadNames(world).forEach { it.endsWith(".enc") shouldBe true }
        a.key = key
        a.synced()
        a.library.entries.getValue(key(5)).chapters.first { it.url == chapter(5, 3) }.read shouldBe true
        b.sync() shouldBe SyncOutcome.Stopped(SyncError.NeedsPassphrase(key.keyId))
    }

    @Test
    fun `an empty remote has nothing to rewrite`() = runBlocking<Unit> {
        val world = SimWorld(root)
        val a = world.device("a")

        a.engine.reencrypt(current = null, next = newKey("pass")) shouldBe RewriteOutcome.Unchanged
        world.backend.files() shouldBe emptyList()
    }
}
