package haruka.sync.work

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.backup.restore.BackupRestoreWorker
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import eu.kanade.tachiyomi.util.system.workManager
import haruka.di.harukaGraph
import haruka.sync.SyncRunResult
import haruka.sync.local.SYNC_LOG_TAG
import haruka.sync.syncNotifier
import logcat.LogPriority
import logcat.logcat
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR

/**
 * Runs one sync through [haruka.sync.SyncManager]; enqueued only by [SyncScheduler]. Like Mihon's backup workers it
 * runs as a foreground service while it works, so a long first sync survives the 10-minute background limit.
 */
class SyncWorker(context: Context, workerParams: WorkerParameters) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val reason = inputData.getString(SyncScheduler.REASON_KEY)
            ?.let { name -> SyncReason.entries.firstOrNull { it.name == name } }
            ?: SyncReason.MANUAL
        val manager = applicationContext.harukaGraph.syncManager
        if (!manager.shouldRun(reason)) return Result.success()
        // A restore rewrites the library under the sync's feet; run again once it is done.
        if (BackupRestoreWorker.isRunning(applicationContext.workManager)) {
            logcat(LogPriority.INFO, SYNC_LOG_TAG) { "run deferred reason=$reason (restore running)" }
            return Result.retry()
        }

        setForegroundSafely()
        return try {
            val result = manager.runSync(reason)
            applicationContext.syncNotifier.onRunFinished()
            when (result) {
                SyncRunResult.DONE, SyncRunResult.SKIPPED -> Result.success()
                // The next trigger or periodic run tries again; the last result already says what failed.
                SyncRunResult.RETRY -> if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.success()
            }
        } finally {
            applicationContext.cancelNotification(NOTIFICATION_ID)
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = applicationContext.notificationBuilder(Notifications.CHANNEL_BACKUP_RESTORE_PROGRESS) {
            setSmallIcon(R.drawable.ic_mihon)
            setContentTitle(applicationContext.stringResource(MR.strings.syncing_library))
            setProgress(0, 0, true)
            setOngoing(true)
            setOnlyAlertOnce(true)
        }.build()
        return ForegroundInfo(
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
    }

    private companion object {
        // Fork id, clear of upstream's negative ids in Notifications.
        const val NOTIFICATION_ID = -0x4853
        const val MAX_RETRIES = 3
    }
}
