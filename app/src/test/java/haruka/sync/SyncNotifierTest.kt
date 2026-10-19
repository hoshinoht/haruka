package haruka.sync

import haruka.sync.model.EntryKey
import haruka.sync.remote.SyncError
import haruka.sync.store.RemovalOrigin
import haruka.sync.ui.HeldRemoval
import haruka.sync.ui.SyncLastResult
import haruka.sync.work.SyncStatus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

class SyncNotifierTest {

    private val held = listOf(
        HeldRemoval(EntryKey(1L, "/a"), "Title A", RemovalOrigin.LOCAL),
        HeldRemoval(EntryKey(2L, "/b"), "Title B", RemovalOrigin.REMOTE),
    )

    @Test
    fun `a clean sync or a cleared status removes the notification`() {
        syncNotice(SyncStatus(SyncLastResult.Synced(union = false, conflicts = 2))) shouldBe SyncNotice.Clear
        syncNotice(SyncStatus()) shouldBe SyncNotice.Clear
    }

    @Test
    fun `a sync that holds removals asks for a decision`() {
        val status = SyncStatus(SyncLastResult.Synced(union = false, conflicts = 0), pendingRemovals = held)

        syncNotice(status) shouldBe SyncNotice.Show.HeldRemovals(2)
    }

    @Test
    fun `needing sign-in or any stop notifies, ahead of removals kept from an earlier sync`() {
        syncNotice(SyncStatus(SyncLastResult.NeedsSignIn, pendingRemovals = held)) shouldBe
            SyncNotice.Show.NeedsSignIn
        val error = SyncError.NeedsPassphrase("key-1")
        syncNotice(SyncStatus(SyncLastResult.Stopped(error), pendingRemovals = held)) shouldBe
            SyncNotice.Show.Stopped(error)
    }

    @Test
    fun `transient failures leave the notification alone`() {
        syncNotice(SyncStatus(SyncLastResult.Contended, pendingRemovals = held)) shouldBe SyncNotice.Unchanged
        syncNotice(SyncStatus(SyncLastResult.Failed("java.io.IOException: offline"))) shouldBe SyncNotice.Unchanged
    }

    @Test
    fun `each kind of problem has its own key and key ids never change it`() {
        val errors = listOf(
            SyncError.FormatTooNew(formatVersion = 9),
            SyncError.NeedsPassphrase("key-1"),
            SyncError.RemoteRolledBack(foundSeq = null, expectedSeq = 7),
            SyncError.WrongPassphrase("key-2"),
            SyncError.Corrupt("bad tag"),
            SyncError.RemotePlaintext,
        )
        val keys = errors.map { SyncNotice.Show.Stopped(it).key } + SyncNotice.Show.NeedsSignIn.key

        keys.toSet().size shouldBe keys.size
        SyncNotice.Show.Stopped(SyncError.NeedsPassphrase("other")).key shouldBe
            SyncNotice.Show.Stopped(SyncError.NeedsPassphrase("key-1")).key
        SyncNotice.Show.HeldRemovals(3).key shouldNotBe SyncNotice.Show.HeldRemovals(2).key
    }
}
