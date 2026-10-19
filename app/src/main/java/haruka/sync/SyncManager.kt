package haruka.sync

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provider
import dev.zacsweers.metro.SingleIn
import haruka.preference.HarukaPreferences
import haruka.sync.auth.DriveAuthRequiredException
import haruka.sync.auth.DriveAuthResult
import haruka.sync.auth.GoogleDriveAuth
import haruka.sync.backup.RestoreMarker
import haruka.sync.crypto.SyncKey
import haruka.sync.drive.DriveRemoteStore
import haruka.sync.engine.SyncDevice
import haruka.sync.engine.SyncListener
import haruka.sync.engine.SyncOutcome
import haruka.sync.engine.SyncPhase
import haruka.sync.engine.SyncRequest
import haruka.sync.engine.UnlockResult
import haruka.sync.local.LocalLibraryApplier
import haruka.sync.local.SYNC_LOG_TAG
import haruka.sync.remote.RemoteLayout
import haruka.sync.remote.SyncError
import haruka.sync.store.BaseStore
import haruka.sync.store.PendingRemoval
import haruka.sync.ui.EncryptionStatus
import haruka.sync.ui.HeldRemoval
import haruka.sync.ui.PassphraseResult
import haruka.sync.ui.SignInResult
import haruka.sync.ui.SyncInterval
import haruka.sync.ui.SyncLastResult
import haruka.sync.ui.SyncSettingsModel
import haruka.sync.ui.SyncSettingsState
import haruka.sync.work.RemovalDecision
import haruka.sync.work.SyncKeyVault
import haruka.sync.work.SyncReason
import haruka.sync.work.SyncSchedule
import haruka.sync.work.SyncScheduler
import haruka.sync.work.SyncStatus
import haruka.sync.work.SyncStatusStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import logcat.LogPriority
import logcat.asLog
import logcat.logcat
import mihon.core.metro.AppCoroutineScope
import okhttp3.OkHttpClient
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.manga.repository.MangaRepository
import java.io.IOException

/** One step of a running sync, for the progress banner. */
data class SyncProgress(val phase: SyncPhase, val done: Int, val total: Int)

/** What a [SyncManager.runSync] call asks of the worker. */
enum class SyncRunResult {
    /** Synced, or stopped until the user acts; nothing to retry. */
    DONE,

    /** A transient failure (network, contention): run again with backoff. */
    RETRY,

    /** Automatic syncs are off; nothing ran. */
    SKIPPED,
}

/**
 * The app side of sync: builds a [SyncEngine] for each run over Drive ([GoogleDriveAuth] + [DriveRemoteStore]), the
 * base store under `filesDir/haruka/sync/<applicationId>` and a fresh [LocalLibraryApplier], maps every outcome and
 * failure to a persisted [SyncLastResult], keeps the encryption key wrapped by the Android Keystore, and schedules
 * [haruka.sync.work.SyncWorker]. It is the settings screen's [SyncSettingsModel].
 *
 * Runs, unlocks and sign-out share one process-wide lock: the engine and its base store take one caller at a time.
 */
@Inject
@SingleIn(AppScope::class)
class SyncManager(
    private val context: Context,
    private val preferences: HarukaPreferences,
    private val restoreMarker: RestoreMarker,
    private val applierProvider: Provider<LocalLibraryApplier>,
    private val mangaRepository: MangaRepository,
    preferenceStore: PreferenceStore,
    @AppCoroutineScope scope: CoroutineScope,
) : SyncSettingsModel {

    /** Shared with screens that sign in, so the token cached here is the one syncs use. */
    val auth = GoogleDriveAuth(context)

    private val status = SyncStatusStore(preferenceStore)
    private val vault = SyncKeyVault(preferenceStore)
    private val mutex = Mutex()
    private val layout = RemoteLayout(context.packageName)

    // No cache and no logging interceptor: requests carry the bearer token and library data.
    private val client = OkHttpClient()

    private val workManager: WorkManager get() = WorkManager.getInstance(context)

    private val mutableProgress = MutableStateFlow<SyncProgress?>(null)

    /** The running sync's phase; null when none runs. */
    val progress: StateFlow<SyncProgress?> = mutableProgress.asStateFlow()

    override val state: StateFlow<SyncSettingsState> = combine(
        combine(
            preferences.syncEnabled.changes(),
            preferences.syncIntervalHours.changes(),
            preferences.syncWifiOnly.changes(),
            preferences.syncChargingOnly.changes(),
        ) { enabled, hours, wifiOnly, chargingOnly -> SyncSchedule(enabled, hours, wifiOnly, chargingOnly) },
        status.account.changes(),
        status.statusChanges(),
        preferences.syncEncryption.changes(),
        workManager.getWorkInfosByTagFlow(SyncScheduler.TAG)
            .map { infos -> infos.any { it.state == WorkInfo.State.RUNNING } },
    ) { schedule, account, syncStatus, encryption, running ->
        stateOf(schedule, account, syncStatus, encryption, running)
    }
        .stateIn(scope, SharingStarted.Eagerly, SyncSettingsState())

    /** Whether a sync for [reason] should run at all; manual syncs always do. */
    fun shouldRun(reason: SyncReason): Boolean = reason == SyncReason.MANUAL || preferences.syncEnabled.get()

    /** One sync, called by the worker; records its result before returning. */
    suspend fun runSync(reason: SyncReason): SyncRunResult {
        if (!shouldRun(reason)) {
            logcat(LogPriority.INFO, SYNC_LOG_TAG) { "run skipped reason=$reason (automatic sync off)" }
            return SyncRunResult.SKIPPED
        }
        return mutex.withLock {
            logcat(LogPriority.INFO, SYNC_LOG_TAG) { "run start reason=$reason" }
            try {
                runLocked()
            } finally {
                mutableProgress.value = null
            }
        }
    }

    private suspend fun runLocked(): SyncRunResult {
        val key = if (preferences.syncEncryption.get()) {
            // Encryption is on but the key is gone (data cleared, Keystore reset): never fall back to plaintext.
            vault.load() ?: return record(SyncLastResult.Stopped(SyncError.NeedsPassphrase(keyId = "")))
        } else {
            null
        }
        val decision = status.removalDecision
        val uploadUnderNewEpoch = status.uploadUnderNewEpoch
        val request = SyncRequest(
            key = key,
            // Read right before each run: a restore that just finished must force union mode.
            lastRestoreAtMillis = restoreMarker.lastRestoreAtMillis(),
            confirmRemovals = decision?.takeIf { it.confirm }?.keys.orEmpty().toSet(),
            rejectRemovals = decision?.takeUnless { it.confirm }?.keys.orEmpty().toSet(),
            uploadUnderNewEpoch = uploadUnderNewEpoch,
        )
        return try {
            when (val outcome = engine().sync(request)) {
                is SyncOutcome.Synced -> {
                    status.removalDecision = null
                    status.uploadUnderNewEpoch = false
                    record(
                        SyncLastResult.Synced(outcome.union, outcome.conflicts.size),
                        held = heldRemovals(outcome.pendingRemovals),
                    )
                }
                is SyncOutcome.Stopped -> record(SyncLastResult.Stopped(outcome.error))
                is SyncOutcome.Contended -> record(SyncLastResult.Contended)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: DriveAuthRequiredException) {
            record(SyncLastResult.NeedsSignIn)
        } catch (e: IOException) {
            logcat(LogPriority.WARN, SYNC_LOG_TAG) { "run failed: ${e.asLog()}" }
            record(SyncLastResult.Failed(e.toString()), retry = true)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, SYNC_LOG_TAG) { "run failed: ${e.asLog()}" }
            record(SyncLastResult.Failed(e.toString()))
        }
    }

    private fun record(
        result: SyncLastResult,
        held: List<HeldRemoval>? = null,
        retry: Boolean = result == SyncLastResult.Contended,
    ): SyncRunResult {
        status.record(result, System.currentTimeMillis(), held)
        logcat(LogPriority.INFO, SYNC_LOG_TAG) { "run result=${result.logName()} retry=$retry" }
        return if (retry) SyncRunResult.RETRY else SyncRunResult.DONE
    }

    override suspend fun signIn(): SignInResult = signInResult(auth.authorize())

    override suspend fun completeSignIn(data: Intent?): SignInResult = signInResult(auth.completeAuthorization(data))

    private suspend fun signInResult(result: DriveAuthResult): SignInResult = when (result) {
        is DriveAuthResult.NeedsUserAction -> SignInResult.NeedsUserAction(result.pendingIntent)
        is DriveAuthResult.Failed -> SignInResult.Failed(result.cause.toString())
        is DriveAuthResult.Authorized -> try {
            val email = remote().accountEmail()
            status.account.set(email)
            SyncScheduler.updatePeriodic(workManager, SyncSchedule.of(preferences))
            SignInResult.SignedIn(email)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SignInResult.Failed(e.toString())
        }
    }

    override suspend fun signOut() {
        SyncScheduler.cancelAll(workManager)
        mutex.withLock {
            val email = status.account.get()
            if (email.isNotEmpty()) {
                try {
                    auth.revoke(email)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // The local state goes either way; a grant left behind is removable in the Google account.
                    logcat(LogPriority.WARN, SYNC_LOG_TAG) { "revoke failed: ${e.asLog()}" }
                }
            }
            vault.clear()
            preferences.syncEncryption.set(false)
            preferences.syncEnabled.set(false)
            status.clear()
            withContext(Dispatchers.IO) { baseStoreDirectory().deleteRecursively() }
        }
    }

    override fun setEnabled(enabled: Boolean) {
        preferences.syncEnabled.set(enabled)
        if (!enabled) workManager.cancelUniqueWork(SyncScheduler.DEBOUNCED)
        reschedule()
    }

    override fun setInterval(interval: SyncInterval) {
        preferences.syncIntervalHours.set(interval.hours)
        reschedule()
    }

    override fun setWifiOnly(wifiOnly: Boolean) {
        preferences.syncWifiOnly.set(wifiOnly)
        reschedule()
    }

    override fun setChargingOnly(chargingOnly: Boolean) {
        preferences.syncChargingOnly.set(chargingOnly)
        reschedule()
    }

    /** Brings the periodic sync in line with the settings; also called at app start. */
    fun reschedule() {
        SyncScheduler.updatePeriodic(workManager, SyncSchedule.of(preferences))
    }

    override fun syncNow() {
        SyncScheduler.syncNow(workManager, SyncReason.MANUAL)
    }

    override fun uploadThisLibrary() {
        status.uploadUnderNewEpoch = true
        syncNow()
    }

    override fun confirmRemovals() = decideRemovals(confirm = true)

    override fun rejectRemovals() = decideRemovals(confirm = false)

    private fun decideRemovals(confirm: Boolean) {
        val keys = status.status().pendingRemovals.map { it.key }
        if (keys.isEmpty()) return
        status.removalDecision = RemovalDecision(confirm, keys)
        syncNow()
    }

    /**
     * An empty Drive gets a new key from [passphrase]; the first sync then writes the library encrypted. A Drive
     * library this device can already open keeps its key when [passphrase] opens it.
     */
    override suspend fun setPassphrase(passphrase: String): PassphraseResult = passphraseCall {
        if (remote().readHead() == null) {
            val key = withContext(Dispatchers.Default) { SyncKey.create(passphrase) }
            keep(key)
            PassphraseResult.Done
        } else {
            when (val result = unlockResult(passphrase)) {
                // Re-encrypting an existing remote is a rewrite commit the engine does not offer yet.
                PassphraseResult.NotEncrypted -> PassphraseResult.Failed(REWRITE_UNSUPPORTED)
                PassphraseResult.WrongPassphrase -> PassphraseResult.Failed(REWRITE_UNSUPPORTED)
                else -> result
            }
        }
    }

    override suspend fun unlock(passphrase: String): PassphraseResult = passphraseCall { unlockResult(passphrase) }

    private suspend fun unlockResult(passphrase: String): PassphraseResult =
        when (val result = withContext(Dispatchers.Default) { engine().unlock(passphrase) }) {
            is UnlockResult.Unlocked -> {
                keep(result.key)
                PassphraseResult.Done
            }
            UnlockResult.NotEncrypted -> PassphraseResult.NotEncrypted
            is UnlockResult.Failed -> when (val error = result.error) {
                is SyncError.WrongPassphrase -> PassphraseResult.WrongPassphrase
                else -> PassphraseResult.Failed(error.toString())
            }
        }

    private suspend fun passphraseCall(block: suspend () -> PassphraseResult): PassphraseResult =
        mutex.withLock {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PassphraseResult.Failed(e.toString())
            }
        }

    private fun keep(key: SyncKey) {
        vault.store(key)
        preferences.syncEncryption.set(true)
        // A sync that stopped for the passphrase can go on now.
        val last = status.status().lastResult
        if (last is SyncLastResult.Stopped && last.error is SyncError.NeedsPassphrase) syncNow()
    }

    private fun remote() = DriveRemoteStore(client, auth, layout)

    private fun baseStoreDirectory() = context.filesDir.resolve("haruka").resolve("sync").resolve(layout.namespace)

    private suspend fun engine(): SyncEngine {
        val store = withContext(Dispatchers.IO) { BaseStore.open(context.filesDir.toPath(), layout.namespace) }
        // A fresh applier per run: its snapshot reader remembers what this run observed.
        val applier = applierProvider()
        return SyncEngine(
            remote = remote(),
            layout = layout,
            store = store,
            local = applier.source,
            applier = applier,
            device = SyncDevice(status.deviceId, Build.MODEL),
            listener = listener,
        )
    }

    private val listener = object : SyncListener {
        override fun onProgress(phase: SyncPhase, done: Int, total: Int) {
            mutableProgress.value = SyncProgress(phase, done, total)
        }

        override fun onLog(message: String) {
            logcat(LogPriority.INFO, SYNC_LOG_TAG) { message }
        }
    }

    private suspend fun heldRemovals(removals: List<PendingRemoval>): List<HeldRemoval> = removals.map {
        val title = mangaRepository.getMangaByUrlAndSourceId(it.key.url, it.key.sourceId)?.title ?: it.key.url
        HeldRemoval(it.key, title, it.origin)
    }

    private fun stateOf(
        schedule: SyncSchedule,
        account: String,
        status: SyncStatus,
        encryption: Boolean,
        running: Boolean,
    ): SyncSettingsState {
        val last = status.lastResult
        val locked = last is SyncLastResult.Stopped && last.error is SyncError.NeedsPassphrase
        return SyncSettingsState(
            account = account.ifEmpty { null },
            enabled = schedule.enabled,
            interval = SyncInterval.entries.firstOrNull { it.hours == schedule.intervalHours } ?: SyncInterval.DEFAULT,
            wifiOnly = schedule.wifiOnly,
            chargingOnly = schedule.chargingOnly,
            running = running,
            lastSyncAtMillis = status.lastSyncedAtMillis,
            lastResult = last,
            encryption = when {
                locked -> EncryptionStatus.LOCKED
                encryption -> EncryptionStatus.ON
                else -> EncryptionStatus.OFF
            },
            pendingRemovals = status.pendingRemovals,
        )
    }

    private companion object {
        const val REWRITE_UNSUPPORTED = "Encrypting or re-keying an existing Drive library is not supported yet"
    }
}

/** A short name for logs; never includes key ids or exception text. */
internal fun SyncLastResult.logName(): String = when (this) {
    is SyncLastResult.Synced -> "synced"
    is SyncLastResult.Stopped -> "stopped:${error.javaClass.simpleName}"
    SyncLastResult.Contended -> "contended"
    SyncLastResult.NeedsSignIn -> "needs-sign-in"
    is SyncLastResult.Failed -> "failed"
}
