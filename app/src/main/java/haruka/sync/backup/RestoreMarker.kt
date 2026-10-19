package haruka.sync.backup

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import mihon.app.di.appGraph
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

/**
 * When a Mihon backup restore last finished on this device (`haruka_last_restore_at`).
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

    /** The value for `SyncRequest.lastRestoreAtMillis`; null when no restore has finished on this device. */
    fun lastRestoreAtMillis(): Long? = lastRestoreAt.get().takeIf { it != NEVER }

    /**
     * Records a finished restore, including failed and cancelled ones: they may have written part of the backup.
     * A needless union sync costs little: removals made here since the last sync come back instead of propagating.
     * The value strictly increases even if the clock went back, so the engine always sees it as newer than the
     * last one it recorded.
     */
    fun onRestoreFinished(nowMillis: Long = System.currentTimeMillis()) {
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
