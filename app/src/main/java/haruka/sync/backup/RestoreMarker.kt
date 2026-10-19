package haruka.sync.backup

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import mihon.app.di.appGraph
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import java.util.concurrent.atomic.AtomicBoolean

/**
 * When a Mihon backup restore last started or finished on this device (`haruka_last_restore_at`).
 *
 * A restore rewrites the library outside sync, so the three-way base no longer describes what this device changed.
 * The sync engine compares this marker with `SyncState.lastRestoreSeenAtMillis` and runs the next sync in union
 * mode, which never pushes removals or regressions, then records the marker as seen.
 *
 * The key is an app-state key, so Mihon backups never carry it and restoring a backup can't reset it.
 */
@Inject
@SingleIn(AppScope::class)
class RestoreMarker(preferenceStore: PreferenceStore) {

    private val lastRestoreAt: Preference<Long> = preferenceStore.getLong(KEY, NEVER)

    // Whether the running restore already moved the marker. In memory on purpose: a process death ends the run, and
    // the next restore must move it again.
    private val runRecorded = AtomicBoolean(false)

    /** The value for `SyncRequest.lastRestoreAtMillis`; null when no restore has run on this device. */
    fun lastRestoreAtMillis(): Long? = lastRestoreAt.get().takeIf { it != NEVER }

    /**
     * Records a running restore on its first progress report; later reports of the same run do nothing. A restore
     * killed mid-way never reaches [onRestoreFinished], and the next sync must still treat the half-restored library
     * as a restore rather than as edits made on this device.
     */
    fun onRestoreProgress(nowMillis: Long = System.currentTimeMillis()) {
        if (runRecorded.compareAndSet(false, true)) bump(nowMillis)
    }

    /**
     * Records a finished restore, including failed and cancelled ones: they may have written part of the backup.
     * Bumping again at the end covers a sync that ran while the restore was still writing.
     * A needless union sync costs little: removals made here since the last sync come back instead of propagating.
     */
    fun onRestoreFinished(nowMillis: Long = System.currentTimeMillis()) {
        runRecorded.set(false)
        bump(nowMillis)
    }

    // The value strictly increases even if the clock went back, so the engine always sees it as newer than the last
    // one it recorded.
    private fun bump(nowMillis: Long) {
        lastRestoreAt.set(maxOf(nowMillis, lastRestoreAt.get() + 1))
    }

    internal companion object {
        val KEY = Preference.appStateKey("haruka_last_restore_at")
        const val NEVER = 0L
    }
}

/** Contributes the marker to the app graph without growing `HarukaGraph`. */
@ContributesTo(AppScope::class)
interface RestoreMarkerGraph {
    val restoreMarker: RestoreMarker
}

val Context.restoreMarker: RestoreMarker get() = (appGraph as RestoreMarkerGraph).restoreMarker
