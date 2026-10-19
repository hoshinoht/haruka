package haruka.sync

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleInitializer
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.startup.Initializer
import androidx.work.WorkManager
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import haruka.preference.HarukaPreferences
import haruka.sync.local.SYNC_LOG_TAG
import haruka.sync.work.SyncReason
import haruka.sync.work.SyncSchedule
import haruka.sync.work.SyncScheduler
import haruka.sync.work.SyncStatusStore
import logcat.LogPriority
import logcat.logcat
import mihon.app.di.appGraph
import tachiyomi.core.common.preference.PreferenceStore

/** An automatic sync trigger; the name is what the log shows. */
enum class SyncTrigger { CHAPTER_COMPLETED, APP_START, APP_STOP }

/**
 * Turns reading and app lifecycle events into sync work, only while automatic syncs are on:
 * - [SyncTrigger.CHAPTER_COMPLETED] (reader hook): debounced, so a reading session ends in one sync.
 * - [SyncTrigger.APP_START]: debounced, unless a sync was attempted in the last [START_INTERVAL_MILLIS].
 * - [SyncTrigger.APP_STOP]: right away when a chapter was completed after the last sync attempt, so other devices
 *   see it before the debounce runs out. With Wi-Fi only on, the pending debounced sync (which honours it) stays.
 *
 * App start and stop come from [ProcessLifecycleOwner] through [SyncTriggersInitializer], so upstream's App needs
 * no hook.
 */
@Inject
@SingleIn(AppScope::class)
class SyncTriggers(
    private val context: Context,
    private val preferences: HarukaPreferences,
    preferenceStore: PreferenceStore,
) {

    private val status = SyncStatusStore(preferenceStore)

    /** When this process last saw a chapter completed; a stop with nothing new since the last attempt syncs nothing. */
    @Volatile
    private var chapterCompletedAtMillis = 0L

    fun onChapterCompleted() = fire(SyncTrigger.CHAPTER_COMPLETED)

    fun onAppStart() = fire(SyncTrigger.APP_START)

    fun onAppStop() = fire(SyncTrigger.APP_STOP)

    internal fun fire(
        trigger: SyncTrigger,
        workManager: WorkManager = WorkManager.getInstance(context),
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        if (trigger == SyncTrigger.CHAPTER_COMPLETED) chapterCompletedAtMillis = nowMillis
        val schedule = SyncSchedule.of(preferences)
        if (!schedule.enabled) return
        val lastAttemptAtMillis = status.status().lastAttemptAtMillis
        when (trigger) {
            SyncTrigger.CHAPTER_COMPLETED -> SyncScheduler.syncSoon(workManager, schedule)
            SyncTrigger.APP_START -> {
                if (lastAttemptAtMillis != null && nowMillis - lastAttemptAtMillis < START_INTERVAL_MILLIS) {
                    return log(trigger, "skipped (synced recently)")
                }
                SyncScheduler.syncSoon(workManager, schedule)
            }
            SyncTrigger.APP_STOP -> {
                if (chapterCompletedAtMillis <= (lastAttemptAtMillis ?: 0L)) {
                    return log(trigger, "skipped (nothing read since last sync)")
                }
                if (schedule.wifiOnly) return log(trigger, "kept ${SyncScheduler.DEBOUNCED} (wifi only)")
                // The immediate run covers what the debounced one would have synced.
                workManager.cancelUniqueWork(SyncScheduler.DEBOUNCED)
                SyncScheduler.syncNow(workManager, SyncReason.TRIGGER)
                return log(trigger, "enqueued ${SyncScheduler.NOW}")
            }
        }
        log(trigger, "enqueued ${SyncScheduler.DEBOUNCED}")
    }

    private fun log(trigger: SyncTrigger, outcome: String) {
        logcat(LogPriority.INFO, SYNC_LOG_TAG) { "trigger=$trigger $outcome" }
    }

    internal companion object {
        const val START_INTERVAL_MILLIS = 5 * 60 * 1000L
    }
}

/** Graph accessor for [SyncTriggersInitializer], which is created by the system rather than injected. */
@ContributesTo(AppScope::class)
interface SyncTriggersGraph {
    val syncTriggers: SyncTriggers
}

/**
 * Feeds app start/stop into [SyncTriggers]; declared in app/src/haruka/AndroidManifest.xml under androidx.startup's
 * InitializationProvider.
 *
 * It runs before `Application.onCreate`, when the graph must not be built yet, so it only registers the observer;
 * the graph is first read on the first start, which follows `Application.onCreate`.
 */
class SyncTriggersInitializer : Initializer<Unit> {

    override fun create(context: Context) {
        val app = context.applicationContext
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) = app.syncTriggers().onAppStart()

                override fun onStop(owner: LifecycleOwner) = app.syncTriggers().onAppStop()
            },
        )
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = listOf(ProcessLifecycleInitializer::class.java)

    private fun Context.syncTriggers() = (appGraph as SyncTriggersGraph).syncTriggers
}
