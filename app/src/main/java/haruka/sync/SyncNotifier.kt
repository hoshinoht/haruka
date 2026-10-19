package haruka.sync

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.notify
import haruka.i18n.HR
import haruka.sync.remote.SyncError
import haruka.sync.ui.SyncLastResult
import haruka.sync.work.SyncStatus
import haruka.sync.work.SyncStatusStore
import mihon.app.di.appGraph
import tachiyomi.core.common.i18n.pluralStringResource
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

/**
 * The notification for sync results only the user can resolve: signing in again, a passphrase, an app update, a
 * damaged or rolled-back remote, or removals the mass-removal guard holds. Tapping it opens the Sync settings
 * screen. A successful sync without held removals clears it; transient failures (network, contention) leave it as
 * it is, since they say nothing about whether the problem is solved.
 *
 * The same problem alerts once: the key of the last posted problem is remembered, so a user who dismissed the
 * notification is not alerted again by every later trigger that hits the same wall.
 */
@Inject
class SyncNotifier(private val context: Context, preferenceStore: PreferenceStore) {

    private val status = SyncStatusStore(preferenceStore)
    private val notifiedPref = preferenceStore.getString(Preference.appStateKey("haruka_sync_notified"), "")

    /** Called after every sync run, once its result is recorded. */
    fun onRunFinished() {
        when (val notice = syncNotice(status.status())) {
            SyncNotice.Unchanged -> Unit
            SyncNotice.Clear -> {
                context.cancelNotification(NOTIFICATION_ID)
                notifiedPref.delete()
            }
            is SyncNotice.Show -> {
                if (notifiedPref.get() == notice.key) return
                post(notice)
                notifiedPref.set(notice.key)
            }
        }
    }

    private fun post(notice: SyncNotice.Show) {
        val (title, text) = when (notice) {
            is SyncNotice.Show.NeedsSignIn ->
                context.stringResource(HR.strings.sync_notification_stopped) to
                    context.stringResource(HR.strings.sync_error_needs_sign_in)
            is SyncNotice.Show.Stopped ->
                context.stringResource(HR.strings.sync_notification_stopped) to errorText(notice.error)
            is SyncNotice.Show.HeldRemovals ->
                context.stringResource(HR.strings.sync_removals_title) to
                    context.pluralStringResource(HR.plurals.sync_notification_removals, notice.count, notice.count)
        }
        context.notify(NOTIFICATION_ID, Notifications.CHANNEL_BACKUP_RESTORE_COMPLETE) {
            setSmallIcon(R.drawable.ic_mihon)
            setContentTitle(title)
            setContentText(text)
            setStyle(NotificationCompat.BigTextStyle().bigText(text))
            setContentIntent(settingsIntent())
            setAutoCancel(true)
            setOnlyAlertOnce(true)
        }
    }

    private fun errorText(error: SyncError): String = when (error) {
        is SyncError.FormatTooNew -> context.stringResource(HR.strings.sync_error_format_too_new)
        is SyncError.NeedsPassphrase -> context.stringResource(HR.strings.sync_error_needs_passphrase)
        is SyncError.RemoteRolledBack -> context.stringResource(HR.strings.sync_error_rolled_back)
        is SyncError.WrongPassphrase -> context.stringResource(HR.strings.sync_error_wrong_passphrase)
        is SyncError.Corrupt -> context.stringResource(HR.strings.sync_error_corrupt, error.detail)
        SyncError.RemotePlaintext -> context.stringResource(HR.strings.sync_error_remote_plaintext)
    }

    private fun settingsIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(ACTION_SHOW_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra("notificationId", NOTIFICATION_ID)
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        /** MainActivity opens [haruka.sync.ui.SyncSettingsScreen] for this action. */
        const val ACTION_SHOW_SETTINGS = "haruka.sync.SHOW_SETTINGS"

        // Fork id next to SyncWorker's foreground id (-0x4853), clear of upstream's negative ids in Notifications.
        private const val NOTIFICATION_ID = -0x4854
    }
}

/** Exposes [SyncNotifier] to the worker; Metro merges this into upstream's `AppGraph`. */
@ContributesTo(AppScope::class)
interface SyncNotifierGraph {
    val syncNotifier: SyncNotifier
}

val Context.syncNotifier: SyncNotifier get() = (appGraph as SyncNotifierGraph).syncNotifier

/** What the sync notification does after a run. */
internal sealed interface SyncNotice {

    /** Nothing needs the user: remove the notification. */
    data object Clear : SyncNotice

    /** A transient failure: keep whatever is shown. */
    data object Unchanged : SyncNotice

    sealed interface Show : SyncNotice {
        /** Identifies the problem, so the same one alerts only once. */
        val key: String

        data object NeedsSignIn : Show {
            override val key = "sign-in"
        }

        data class Stopped(val error: SyncError) : Show {
            override val key = "stopped:" + when (error) {
                is SyncError.FormatTooNew -> "format-too-new"
                is SyncError.NeedsPassphrase -> "needs-passphrase"
                is SyncError.RemoteRolledBack -> "rolled-back"
                is SyncError.WrongPassphrase -> "wrong-passphrase"
                is SyncError.Corrupt -> "corrupt"
                SyncError.RemotePlaintext -> "remote-plaintext"
            }
        }

        data class HeldRemovals(val count: Int) : Show {
            override val key = "removals:$count"
        }
    }
}

/**
 * The notice for the recorded [status]. A stop outranks held removals: removals kept from an earlier sync can only
 * be decided by a sync that gets through.
 */
internal fun syncNotice(status: SyncStatus): SyncNotice = when (val last = status.lastResult) {
    // Never synced, or signed out (the status is cleared then).
    null -> SyncNotice.Clear
    SyncLastResult.NeedsSignIn -> SyncNotice.Show.NeedsSignIn
    is SyncLastResult.Stopped -> SyncNotice.Show.Stopped(last.error)
    is SyncLastResult.Synced -> if (status.pendingRemovals.isEmpty()) {
        SyncNotice.Clear
    } else {
        SyncNotice.Show.HeldRemovals(status.pendingRemovals.size)
    }
    SyncLastResult.Contended, is SyncLastResult.Failed -> SyncNotice.Unchanged
}
