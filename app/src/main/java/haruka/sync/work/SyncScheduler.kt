package haruka.sync.work

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import haruka.preference.HarukaPreferences
import java.util.concurrent.TimeUnit

/** The user's sync settings as far as scheduling is concerned. */
data class SyncSchedule(
    val enabled: Boolean,
    /** 0 turns the periodic sync off. */
    val intervalHours: Int,
    val wifiOnly: Boolean,
    val chargingOnly: Boolean,
) {
    companion object {
        fun of(preferences: HarukaPreferences) = SyncSchedule(
            enabled = preferences.syncEnabled.get(),
            intervalHours = preferences.syncIntervalHours.get(),
            wifiOnly = preferences.syncWifiOnly.get(),
            chargingOnly = preferences.syncChargingOnly.get(),
        )
    }
}

/** Why a sync was enqueued; manual syncs ignore the Wi-Fi/charging options and the enabled switch. */
enum class SyncReason { MANUAL, TRIGGER, PERIODIC }

/**
 * Enqueues [SyncWorker] under three unique names, all tagged [SyncScheduler.TAG]:
 * - [NOW]: "Sync now" and immediate triggers, expedited, any network. A new request replaces a pending or running
 *   one; the engine is crash-safe, so a cancelled run leaves the old base.
 * - [DEBOUNCED]: triggers that fire in bursts (chapter finished); each request restarts the [DEBOUNCE_SECONDS] delay.
 * - [PERIODIC]: every `intervalHours` with the user's Wi-Fi/charging constraints; settings changes update it in place.
 */
object SyncScheduler {

    const val TAG = "HarukaSync"
    const val NOW = "HarukaSyncNow"
    const val DEBOUNCED = "HarukaSyncDebounced"
    const val PERIODIC = "HarukaSyncPeriodic"

    const val DEBOUNCE_SECONDS = 45L

    internal const val REASON_KEY = "reason"

    // WorkManager's periodic flex floor is 5 minutes; a quarter hour lets the job batch with others.
    private const val FLEX_MINUTES = 15L
    private const val BACKOFF_MINUTES = 5L

    fun syncNow(workManager: WorkManager, reason: SyncReason = SyncReason.MANUAL) {
        workManager.enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, nowRequest(reason))
    }

    /** Starts a sync [DEBOUNCE_SECONDS] after the last call, if automatic syncs are on. */
    fun syncSoon(workManager: WorkManager, schedule: SyncSchedule) {
        if (!schedule.enabled) return
        workManager.enqueueUniqueWork(DEBOUNCED, ExistingWorkPolicy.REPLACE, debouncedRequest(schedule))
    }

    /** Creates, updates or cancels the periodic sync to match [schedule]; call after every settings change. */
    fun updatePeriodic(workManager: WorkManager, schedule: SyncSchedule) {
        val request = periodicRequest(schedule)
        if (request == null) {
            workManager.cancelUniqueWork(PERIODIC)
        } else {
            workManager.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }

    fun cancelAll(workManager: WorkManager) {
        workManager.cancelAllWorkByTag(TAG)
    }

    internal fun nowRequest(reason: SyncReason): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<SyncWorker>()
            // Expedited jobs accept only network and storage constraints.
            .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
            .addTag(TAG)
            .setInputData(workDataOf(REASON_KEY to reason.name))
            .build()

    internal fun debouncedRequest(schedule: SyncSchedule): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints(schedule, periodic = false))
            .setInitialDelay(DEBOUNCE_SECONDS, TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
            .addTag(TAG)
            .setInputData(workDataOf(REASON_KEY to SyncReason.TRIGGER.name))
            .build()

    /** Null when the periodic sync is off. */
    internal fun periodicRequest(schedule: SyncSchedule): PeriodicWorkRequest? {
        if (!schedule.enabled || schedule.intervalHours <= 0) return null
        return PeriodicWorkRequestBuilder<SyncWorker>(
            schedule.intervalHours.toLong(),
            TimeUnit.HOURS,
            FLEX_MINUTES,
            TimeUnit.MINUTES,
        )
            .setConstraints(constraints(schedule, periodic = true))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
            .addTag(TAG)
            .setInputData(workDataOf(REASON_KEY to SyncReason.PERIODIC.name))
            .build()
    }

    /**
     * Wi-Fi only maps to an unmetered network. Charging applies to the periodic sync only: a trigger right after
     * reading should not wait for a charger.
     */
    internal fun constraints(schedule: SyncSchedule, periodic: Boolean) = Constraints(
        requiredNetworkType = if (schedule.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED,
        requiresCharging = periodic && schedule.chargingOnly,
    )
}
