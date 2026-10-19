package haruka.sync.work

import haruka.sync.model.EntryKey
import haruka.sync.remote.SyncError
import haruka.sync.store.RemovalOrigin
import haruka.sync.ui.HeldRemoval
import haruka.sync.ui.SyncLastResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore

class SyncStatusStoreTest {

    private val held = listOf(
        HeldRemoval(EntryKey(1L, "/a"), "Title A", RemovalOrigin.LOCAL),
        HeldRemoval(EntryKey(2L, "/b"), "Title B", RemovalOrigin.REMOTE),
    )

    @Test
    fun `every result survives the round trip through storage`() {
        val results = listOf(
            SyncLastResult.Synced(union = true, conflicts = 3),
            SyncLastResult.Stopped(SyncError.FormatTooNew(formatVersion = 9)),
            SyncLastResult.Stopped(SyncError.NeedsPassphrase("key-1")),
            SyncLastResult.Stopped(SyncError.RemoteRolledBack(foundSeq = null, expectedSeq = 7)),
            SyncLastResult.Stopped(SyncError.RemoteRolledBack(foundSeq = 4, expectedSeq = 7)),
            SyncLastResult.Stopped(SyncError.WrongPassphrase("key-2")),
            SyncLastResult.Stopped(SyncError.Corrupt("bad tag")),
            SyncLastResult.Stopped(SyncError.RemotePlaintext),
            SyncLastResult.Contended,
            SyncLastResult.NeedsSignIn,
            SyncLastResult.Failed("java.io.IOException: offline"),
        )
        for (result in results) {
            val status = SyncStatus(result, lastAttemptAtMillis = 10, lastSyncedAtMillis = 5, pendingRemovals = held)

            SyncStatusStore.decodeStatus(SyncStatusStore.encodeStatus(status)) shouldBe status
        }
    }

    @Test
    fun `unreadable stored status reads as never synced`() {
        SyncStatusStore.decodeStatus("") shouldBe SyncStatus()
        SyncStatusStore.decodeStatus("{not json") shouldBe SyncStatus()
        SyncStatusStore.decodeStatus("""{"result":{"kind":"from-the-future"}}""") shouldBe SyncStatus()
    }

    @Test
    fun `a failure keeps the last successful sync time and the held removals`() {
        val store = SyncStatusStore(InMemoryPreferenceStore())
        store.record(SyncLastResult.Synced(union = false, conflicts = 0), atMillis = 100, held = held)

        store.record(SyncLastResult.NeedsSignIn, atMillis = 200)

        store.status() shouldBe SyncStatus(
            lastResult = SyncLastResult.NeedsSignIn,
            lastAttemptAtMillis = 200,
            lastSyncedAtMillis = 100,
            pendingRemovals = held,
        )
    }

    @Test
    fun `a completed sync replaces the held removals`() {
        val store = SyncStatusStore(InMemoryPreferenceStore())
        store.record(SyncLastResult.Synced(union = false, conflicts = 0), atMillis = 100, held = held)

        store.record(SyncLastResult.Synced(union = false, conflicts = 1), atMillis = 300, held = emptyList())

        store.status().pendingRemovals shouldBe emptyList()
        store.status().lastSyncedAtMillis shouldBe 300
    }

    @Test
    fun `the removal decision and the upload flag persist until cleared`() {
        val store = SyncStatusStore(InMemoryPreferenceStore())
        val decision = RemovalDecision(confirm = true, keys = held.map { it.key })

        store.removalDecision = decision
        store.uploadUnderNewEpoch = true

        store.removalDecision shouldBe decision
        store.uploadUnderNewEpoch shouldBe true

        store.clear()

        store.removalDecision shouldBe null
        store.uploadUnderNewEpoch shouldBe false
    }

    @Test
    fun `the device id is created once and kept`() {
        val store = SyncStatusStore(InMemoryPreferenceStore())

        val id = store.deviceId

        id shouldNotBe ""
        store.deviceId shouldBe id
        store.clear()
        store.deviceId shouldBe id
    }
}
