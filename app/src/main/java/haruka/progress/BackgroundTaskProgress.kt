package haruka.progress

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Progress of long-running background jobs, shown as in-app banners.
 *
 * The jobs' notifiers report progress here; WorkManager decides whether a job is actually running,
 * so a killed worker can never leave a stale banner behind.
 */
object BackgroundTaskProgress {

    enum class Kind { LIBRARY_UPDATE, RESTORE, SYNC }

    data class Task(val kind: Kind, val current: Int, val total: Int)

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
    }

    fun onRestoreFinished() {
        restore.value = null
    }

    /** Tasks to show, top to bottom. */
    fun visibleTasks(context: Context): Flow<List<Task>> {
        val workManager = context.workManager
        return combine(
            libraryUpdate,
            restore,
            workManager.isRunningFlow(LIBRARY_UPDATE_TAG),
            workManager.isRunningFlow(RESTORE_TAG),
        ) { update, restore, updateRunning, restoreRunning ->
            listOfNotNull(
                update?.takeIf { updateRunning },
                restore?.takeIf { restoreRunning },
            )
        }
    }

    @OptIn(FlowPreview::class)
    private fun WorkManager.isRunningFlow(tag: String): Flow<Boolean> =
        getWorkInfosByTagFlow(tag)
            .map { infos -> infos.any { it.state == WorkInfo.State.RUNNING } }
            .distinctUntilChanged()
            .debounce { running -> if (running) SHOW_DELAY_MS else 0L }
}
