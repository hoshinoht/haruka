package haruka.progress

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import eu.kanade.tachiyomi.util.system.workManager
import haruka.di.harukaGraph
import haruka.sync.SyncProgress
import haruka.sync.backup.restoreMarker
import haruka.sync.engine.SyncPhase
import haruka.sync.work.SyncScheduler
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Progress of long-running background jobs, shown as in-app banners.
 *
 * The jobs' notifiers (and [haruka.sync.SyncManager.progress] for Drive sync) report progress here; WorkManager
 * decides whether a job is actually running, so a killed worker can never leave a stale banner behind.
 */
object BackgroundTaskProgress {

    enum class Kind { LIBRARY_UPDATE, RESTORE, SYNC }

    /** [syncPhase] is set for Haruka's Drive sync, which labels the banner by phase. */
    data class Task(val kind: Kind, val current: Int, val total: Int, val syncPhase: SyncPhase? = null)

    // Tags of the upstream workers (LibraryUpdateWorker.TAG, BackupRestoreWorker TAG). If upstream renames
    // them the banners simply never show.
    private const val LIBRARY_UPDATE_TAG = "LibraryUpdate"
    private const val RESTORE_TAG = "BackupRestore"

    // Jobs shorter than this never flash a banner.
    private const val SHOW_DELAY_MS = 1_000L

    private val libraryUpdate = MutableStateFlow<Task?>(null)
    private val restore = MutableStateFlow<Task?>(null)

    fun onLibraryUpdateProgress(current: Int, total: Int) {
        libraryUpdate.value = Task(Kind.LIBRARY_UPDATE, current, total)
    }

    fun onLibraryUpdateFinished() {
        libraryUpdate.value = null
    }

    fun onRestoreProgress(current: Int, total: Int, sync: Boolean) {
        restore.value = Task(if (sync) Kind.SYNC else Kind.RESTORE, current, total)
        restoreMarker.onRestoreProgress()
    }

    /** Called on every end of a restore (success, error, cancel); the marker makes the next sync union-merge. */
    fun onRestoreFinished(context: Context) {
        restore.value = null
        context.restoreMarker.onRestoreFinished()
    }

    // The upstream progress hook passes no Context; the marker is app-scoped, so the application context reaches the
    // same instance onRestoreFinished uses.
    private val restoreMarker by lazy { Injekt.get<Context>().restoreMarker }

    /** Tasks to show, top to bottom. */
    fun visibleTasks(context: Context): Flow<List<Task>> {
        val workManager = context.workManager
        return combine(
            libraryUpdate,
            restore,
            context.harukaGraph.syncManager.progress,
            workManager.isRunningFlow(LIBRARY_UPDATE_TAG),
            combine(workManager.isRunningFlow(RESTORE_TAG), workManager.isRunningFlow(SyncScheduler.TAG), ::Pair),
        ) { update, restore, sync, updateRunning, (restoreRunning, syncRunning) ->
            listOfNotNull(
                update?.takeIf { updateRunning },
                restore?.takeIf { restoreRunning },
                syncTask(sync)?.takeIf { syncRunning },
            )
        }
    }

    /**
     * The banner task for a running Drive sync, null when there is nothing to show. Single-step phases (checking
     * the remote head, writing the manifest) report 0/1 then 1/1, which says nothing, so they show as
     * indeterminate; FINISH is the run's last report, right before the worker stops.
     */
    internal fun syncTask(progress: SyncProgress?): Task? {
        val phase = progress?.phase ?: return null
        return when (phase) {
            SyncPhase.FINISH -> null
            SyncPhase.CHECK, SyncPhase.COMMIT -> Task(Kind.SYNC, current = 0, total = 0, syncPhase = phase)
            SyncPhase.DOWNLOAD, SyncPhase.MERGE, SyncPhase.UPLOAD, SyncPhase.APPLY ->
                Task(Kind.SYNC, progress.done, progress.total, phase)
        }
    }

    @OptIn(FlowPreview::class)
    private fun WorkManager.isRunningFlow(tag: String): Flow<Boolean> =
        getWorkInfosByTagFlow(tag)
            .map { infos -> infos.any { it.state == WorkInfo.State.RUNNING } }
            .distinctUntilChanged()
            .debounce { running -> if (running) SHOW_DELAY_MS else 0L }
}
