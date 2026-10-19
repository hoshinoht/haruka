package haruka.sync.store

import haruka.sync.model.BUCKET_COUNT
import haruka.sync.model.ChapterRecord
import haruka.sync.model.EntryKey
import haruka.sync.model.EntryRecord
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class BaseStoreTest {

    @TempDir
    lateinit var filesDir: Path

    private fun openStore() = BaseStore.open(filesDir, "app.haruka.dev")

    @Test
    fun `store lives under the namespace directory`() {
        openStore().directory shouldBe filesDir.resolve("haruka/sync/app.haruka.dev")
    }

    @Test
    fun `namespace must be a single safe path segment`() {
        for (bad in listOf("", ".", "..", "a/b", "../x", "a b")) {
            shouldThrow<IllegalArgumentException> { BaseStore.open(filesDir, bad) }
        }
    }

    @Test
    fun `fresh store has no base and a default state`() {
        val store = openStore()

        store.current shouldBe null
        store.readState() shouldBe SyncState()
    }

    @Test
    fun `activated snapshot round trips through reopen`() {
        val store = openStore()
        val active = store.activate(store.stageFull(seq = 1, tag = "one"))

        active.contents() shouldBe expectedContents(1, "one")
        store.current!!.contents() shouldBe expectedContents(1, "one")
        val reopened = openStore().current!!
        reopened.name shouldBe "base-1-0"
        reopened.contents() shouldBe expectedContents(1, "one")
        Files.readAllBytes(store.directory.resolve("CURRENT")).decodeToString() shouldBe "base-1-0"
    }

    @Test
    fun `localPresent flags survive the round trip`() {
        val store = openStore()
        store.activate(store.stageFull(seq = 1, tag = "one"))

        val entry = openStore().current!!.readBucket(7).entries.single()
        entry.isLocalPresent("/c/1") shouldBe true
        entry.isLocalPresent("/c/2") shouldBe true
        entry.isLocalPresent("/c/3") shouldBe false
    }

    @Test
    fun `state round trips with every field set`() {
        val state = SyncState(
            remoteEpoch = "epoch-1",
            baseCommitSeq = 12,
            lastOwnCommitSeq = 11,
            pendingCommit = PendingCommit(commitId = "commit-13", commitSeq = 13),
            categoryLocalIds = mapOf("cat-1" to 4L, "cat-2" to 9L),
            pendingRemovals = listOf(
                PendingRemoval(EntryKey(1, "/m/1"), RemovalOrigin.LOCAL),
                PendingRemoval(EntryKey(2, "/m/2"), RemovalOrigin.REMOTE),
            ),
            lastSyncAtMillis = 1_700_000_000_000,
            lastResult = "Success",
            lastRestoreSeenAtMillis = 1_600_000_000_000,
        )
        openStore().writeState(state)

        openStore().readState() shouldBe state
        openStore().directory.childNames() shouldBe setOf("state.json")
    }

    @Test
    fun `state ignores fields written by a newer build`() {
        val store = openStore()
        Files.write(
            store.directory.resolve("state.json"),
            """{"baseCommitSeq":3,"futureField":true}""".encodeToByteArray(),
        )

        store.readState() shouldBe SyncState(baseCommitSeq = 3)
    }

    @Test
    fun `activation replaces the previous base and deletes it`() {
        val store = openStore()
        store.activate(store.stageFull(seq = 1, tag = "one"))
        store.activate(store.stageFull(seq = 2, tag = "two"))

        store.directory.childNames() shouldBe setOf("CURRENT", "base-2-0")
        openStore().current!!.contents() shouldBe expectedContents(2, "two")
    }

    @Test
    fun `staging the same seq again uses a new directory`() {
        val store = openStore()
        store.activate(store.stageFull(seq = 3, tag = "first"))
        val again = store.stageFull(seq = 3, tag = "second")

        again.name shouldBe "base-3-1"
        store.activate(again)
        store.directory.childNames() shouldBe setOf("CURRENT", "base-3-1")
        openStore().current!!.contents() shouldBe expectedContents(3, "second")
    }

    @Test
    fun `activation rejects an incomplete snapshot and keeps the old base`() {
        val store = openStore()
        store.activate(store.stageFull(seq = 1, tag = "one"))
        val staged = store.stage(header(2), body(2))
        for (index in 0..<BUCKET_COUNT - 1) staged.writeBucket(bucket(index, "two"))
        staged.writeCategories(categories("two"))
        staged.writePrefs(prefs("two"))

        val error = shouldThrow<IllegalStateException> { store.activate(staged) }
        error.message shouldBe "Snapshot base-2-0 is incomplete, missing [b127.pb.gz]"

        store.current!!.contents() shouldBe expectedContents(1, "one")
        val reopened = openStore()
        reopened.current!!.contents() shouldBe expectedContents(1, "one")
        reopened.directory.childNames() shouldBe setOf("CURRENT", "base-1-0")
    }

    @Test
    fun `carried buckets outlive their source and patches never touch it`() {
        val store = openStore()
        val first = store.activate(store.stageFull(seq = 1, tag = "one"))
        val staged = store.stage(header(2), body(2))
        for (index in 0..<BUCKET_COUNT) staged.carryBucket(first, index)
        staged.writeCategories(categories("two"))
        staged.writePrefs(prefs("two"))

        val patched = bucket(5, "patched")
        staged.writeBucket(patched)

        first.readBucket(5) shouldBe bucket(5, "one")
        staged.readBucket(5) shouldBe patched
        staged.readBucket(6) shouldBe bucket(6, "one")

        store.activate(staged)
        val reopened = openStore().current!!
        reopened.readBucket(5) shouldBe patched
        reopened.readBucket(6) shouldBe bucket(6, "one")
        reopened.readCategories() shouldBe categories("two")
    }

    @Test
    fun `an activated snapshot is immutable`() {
        val store = openStore()
        val staged = store.stageFull(seq = 1, tag = "one")
        store.activate(staged)

        shouldThrow<IllegalStateException> { staged.writeBucket(bucket(0, "late")) }
        shouldThrow<IllegalStateException> { store.activate(staged) }
    }

    @Test
    fun `open deletes half-written snapshots and temp files`() {
        val store = openStore()
        store.activate(store.stageFull(seq = 1, tag = "one"))
        store.writeState(SyncState(baseCommitSeq = 1))
        val dir = store.directory
        // A staging run that died after two files, one of them cut short.
        Files.createDirectory(dir.resolve("base-2-0"))
        Files.write(dir.resolve("base-2-0/header.pb"), byteArrayOf(8, 1))
        Files.write(dir.resolve("base-2-0/b000.pb.gz.tmp"), byteArrayOf(0x1f))
        // Pointer and state replacements that died before their rename.
        Files.write(dir.resolve("CURRENT.tmp"), "base-".encodeToByteArray())
        Files.write(dir.resolve("state.json.tmp"), "{\"baseCommit".encodeToByteArray())

        val reopened = openStore()

        dir.childNames() shouldBe setOf("CURRENT", "state.json", "base-1-0")
        reopened.current!!.contents() shouldBe expectedContents(1, "one")
        reopened.readState() shouldBe SyncState(baseCommitSeq = 1)
    }

    @Test
    fun `open removes a leftover previous base`() {
        val store = openStore()
        store.activate(store.stageFull(seq = 1, tag = "one"))
        val dir = store.directory
        dir.resolve("base-1-0").copyTreeTo(dir.resolve("base-0-0"))

        openStore().current!!.name shouldBe "base-1-0"
        dir.childNames() shouldBe setOf("CURRENT", "base-1-0")
    }

    @Test
    fun `CURRENT naming a missing snapshot reads as no base`() {
        val store = openStore()
        Files.write(store.directory.resolve("CURRENT"), "base-4-0".encodeToByteArray())
        Files.createDirectory(store.directory.resolve("base-5-0"))

        openStore().current shouldBe null
        store.directory.childNames() shouldBe emptySet()
    }

    @Test
    fun `buckets are written in canonical form`() {
        val store = openStore()
        val staged = store.stage(header(1), body(1))
        val b = EntryRecord(EntryKey(2, "/b"), chapters = listOf(ChapterRecord("/2"), ChapterRecord("/1")))
        val a = EntryRecord(EntryKey(1, "/a"), categoryUuids = listOf("y", "x", "x"))
        staged.writeBucket(BaseBucket(0, listOf(BaseEntry(b, listOf("/2", "/1", "/2")), BaseEntry(a))))

        staged.readBucket(0) shouldBe BaseBucket(
            index = 0,
            entries = listOf(
                BaseEntry(a.copy(categoryUuids = listOf("x", "y"))),
                BaseEntry(b.copy(chapters = listOf(ChapterRecord("/1"), ChapterRecord("/2"))), listOf("/1", "/2")),
            ),
        )
    }

    @Test
    fun `a bucket repeating an entry key is rejected`() {
        val staged = openStore().stage(header(1), body(1))
        val entry = BaseEntry(EntryRecord(EntryKey(1, "/a")))

        shouldThrow<IllegalArgumentException> { staged.writeBucket(BaseBucket(0, listOf(entry, entry))) }
    }
}
