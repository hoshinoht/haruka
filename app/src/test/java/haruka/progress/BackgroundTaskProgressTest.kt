package haruka.progress

import haruka.progress.BackgroundTaskProgress.Kind
import haruka.progress.BackgroundTaskProgress.Task
import haruka.progress.BackgroundTaskProgress.syncTask
import haruka.sync.SyncProgress
import haruka.sync.engine.SyncPhase
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class BackgroundTaskProgressTest {

    @Test
    fun `no running sync shows no banner`() {
        syncTask(null) shouldBe null
    }

    @Test
    fun `the final report does not flash a finished banner`() {
        syncTask(SyncProgress(SyncPhase.FINISH, 1, 1)) shouldBe null
    }

    @Test
    fun `counted phases keep their counts and phase`() {
        for (phase in listOf(SyncPhase.DOWNLOAD, SyncPhase.MERGE, SyncPhase.UPLOAD, SyncPhase.APPLY)) {
            syncTask(SyncProgress(phase, 3, 40)) shouldBe Task(Kind.SYNC, 3, 40, phase)
            syncTask(SyncProgress(phase, 0, 40)) shouldBe Task(Kind.SYNC, 0, 40, phase)
        }
    }

    @Test
    fun `a counted phase with nothing to do stays indeterminate`() {
        syncTask(SyncProgress(SyncPhase.DOWNLOAD, 0, 0)) shouldBe Task(Kind.SYNC, 0, 0, SyncPhase.DOWNLOAD)
    }

    @Test
    fun `single-step phases are indeterminate instead of showing 1 of 1`() {
        for (phase in listOf(SyncPhase.CHECK, SyncPhase.COMMIT)) {
            syncTask(SyncProgress(phase, 0, 1)) shouldBe Task(Kind.SYNC, 0, 0, phase)
            syncTask(SyncProgress(phase, 1, 1)) shouldBe Task(Kind.SYNC, 0, 0, phase)
        }
    }
}
