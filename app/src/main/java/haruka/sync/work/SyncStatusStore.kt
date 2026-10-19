package haruka.sync.work

import haruka.sync.model.EntryKey
import haruka.sync.remote.SyncError
import haruka.sync.store.RemovalOrigin
import haruka.sync.ui.HeldRemoval
import haruka.sync.ui.SyncLastResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import java.util.UUID

/**
 * Device-local sync bookkeeping the UI reads: the last result, the held removals with their titles, the account,
 * this install's device id and the user's pending decisions for the next sync. All app-state keys, so Mihon backups
 * never carry them.
 */
class SyncStatusStore(preferenceStore: PreferenceStore) {

    private val deviceIdPref = preferenceStore.getString(Preference.appStateKey("haruka_sync_device_id"), "")
    val account: Preference<String> = preferenceStore.getString(Preference.appStateKey("haruka_sync_account"), "")
    private val statusPref = preferenceStore.getString(Preference.appStateKey("haruka_sync_status"), "")
    private val removalDecisionPref =
        preferenceStore.getString(Preference.appStateKey("haruka_sync_removal_decision"), "")
    private val uploadNewEpochPref =
        preferenceStore.getBoolean(Preference.appStateKey("haruka_sync_upload_new_epoch"), false)

    /** Stable id of this install in the manifest's device list; created on first use. */
    val deviceId: String
        get() = deviceIdPref.get().ifEmpty { UUID.randomUUID().toString().also(deviceIdPref::set) }

    fun status(): SyncStatus = decodeStatus(statusPref.get())

    fun statusChanges(): Flow<SyncStatus> = statusPref.changes().map(::decodeStatus)

    /** Records [result]; a sync that reached the remote ([held] non-null) also replaces the held removals. */
    fun record(result: SyncLastResult, atMillis: Long, held: List<HeldRemoval>? = null) {
        val previous = status()
        val next = SyncStatus(
            lastResult = result,
            lastAttemptAtMillis = atMillis,
            lastSyncedAtMillis = if (result is SyncLastResult.Synced) atMillis else previous.lastSyncedAtMillis,
            pendingRemovals = held ?: previous.pendingRemovals,
        )
        statusPref.set(encodeStatus(next))
    }

    /** The user's answer to the held removals, sent with the next sync and cleared once one completes. */
    var removalDecision: RemovalDecision?
        get() = removalDecisionPref.get().takeIf { it.isNotEmpty() }?.let { text ->
            try {
                json.decodeFromString(RemovalDecision.serializer(), text)
            } catch (e: SerializationException) {
                null
            }
        }
        set(value) {
            if (value == null) {
                removalDecisionPref.delete()
            } else {
                removalDecisionPref.set(json.encodeToString(RemovalDecision.serializer(), value))
            }
        }

    /** "Upload this device's library" is pending for the next sync. */
    var uploadUnderNewEpoch: Boolean
        get() = uploadNewEpochPref.get()
        set(value) = uploadNewEpochPref.set(value)

    /** Sign-out: forgets everything but the device id, which stays this install's identity in the manifest. */
    fun clear() {
        account.delete()
        statusPref.delete()
        removalDecisionPref.delete()
        uploadNewEpochPref.delete()
    }

    internal companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun encodeStatus(status: SyncStatus): String =
            json.encodeToString(StoredStatus.serializer(), StoredStatus.of(status))

        fun decodeStatus(text: String): SyncStatus {
            if (text.isEmpty()) return SyncStatus()
            // A status written by another build that this one can't read is shown as "never synced".
            return try {
                json.decodeFromString(StoredStatus.serializer(), text).toStatus()
            } catch (e: SerializationException) {
                SyncStatus()
            } catch (e: IllegalArgumentException) {
                SyncStatus()
            }
        }
    }
}

data class SyncStatus(
    val lastResult: SyncLastResult? = null,
    val lastAttemptAtMillis: Long? = null,
    val lastSyncedAtMillis: Long? = null,
    val pendingRemovals: List<HeldRemoval> = emptyList(),
)

/** Confirm or reject the held removals listed in [keys] (the set the user saw). */
@Serializable
data class RemovalDecision(val confirm: Boolean, val keys: List<EntryKey>)

/** The persisted form; field names are the storage format, so renaming one forgets the stored status. */
@Serializable
private data class StoredStatus(
    val result: StoredResult? = null,
    val attemptAt: Long? = null,
    val syncedAt: Long? = null,
    val held: List<StoredRemoval> = emptyList(),
) {
    fun toStatus() = SyncStatus(
        lastResult = result?.toResult(),
        lastAttemptAtMillis = attemptAt,
        lastSyncedAtMillis = syncedAt,
        pendingRemovals = held.map { HeldRemoval(EntryKey(it.sourceId, it.url), it.title, it.origin) },
    )

    companion object {
        fun of(status: SyncStatus) = StoredStatus(
            result = status.lastResult?.let(StoredResult::of),
            attemptAt = status.lastAttemptAtMillis,
            syncedAt = status.lastSyncedAtMillis,
            held = status.pendingRemovals.map { StoredRemoval(it.key.sourceId, it.key.url, it.title, it.origin) },
        )
    }
}

@Serializable
private data class StoredRemoval(val sourceId: Long, val url: String, val title: String, val origin: RemovalOrigin)

@Serializable
private data class StoredResult(
    val kind: String,
    val union: Boolean = false,
    val conflicts: Int = 0,
    val error: String? = null,
    val keyId: String? = null,
    val formatVersion: Int? = null,
    val supported: Int? = null,
    val foundSeq: Long? = null,
    val expectedSeq: Long? = null,
    val message: String? = null,
) {
    fun toResult(): SyncLastResult = when (kind) {
        SYNCED -> SyncLastResult.Synced(union, conflicts)
        STOPPED -> SyncLastResult.Stopped(
            when (error) {
                FORMAT_TOO_NEW -> SyncError.FormatTooNew(requireNotNull(formatVersion), requireNotNull(supported))
                NEEDS_PASSPHRASE -> SyncError.NeedsPassphrase(keyId.orEmpty())
                ROLLED_BACK -> SyncError.RemoteRolledBack(foundSeq, requireNotNull(expectedSeq))
                WRONG_PASSPHRASE -> SyncError.WrongPassphrase(keyId.orEmpty())
                CORRUPT -> SyncError.Corrupt(message.orEmpty())
                REMOTE_PLAINTEXT -> SyncError.RemotePlaintext
                else -> throw IllegalArgumentException("Unknown sync error $error")
            },
        )
        CONTENDED -> SyncLastResult.Contended
        NEEDS_SIGN_IN -> SyncLastResult.NeedsSignIn
        FAILED -> SyncLastResult.Failed(message.orEmpty())
        else -> throw IllegalArgumentException("Unknown sync result $kind")
    }

    companion object {
        const val SYNCED = "synced"
        const val STOPPED = "stopped"
        const val CONTENDED = "contended"
        const val NEEDS_SIGN_IN = "needs_sign_in"
        const val FAILED = "failed"

        const val FORMAT_TOO_NEW = "format_too_new"
        const val NEEDS_PASSPHRASE = "needs_passphrase"
        const val ROLLED_BACK = "rolled_back"
        const val WRONG_PASSPHRASE = "wrong_passphrase"
        const val CORRUPT = "corrupt"
        const val REMOTE_PLAINTEXT = "remote_plaintext"

        fun of(result: SyncLastResult): StoredResult = when (result) {
            is SyncLastResult.Synced -> StoredResult(SYNCED, union = result.union, conflicts = result.conflicts)
            is SyncLastResult.Stopped -> when (val error = result.error) {
                is SyncError.FormatTooNew -> StoredResult(
                    STOPPED,
                    error = FORMAT_TOO_NEW,
                    formatVersion = error.formatVersion,
                    supported = error.supported,
                )
                is SyncError.NeedsPassphrase -> StoredResult(STOPPED, error = NEEDS_PASSPHRASE, keyId = error.keyId)
                is SyncError.RemoteRolledBack -> StoredResult(
                    STOPPED,
                    error = ROLLED_BACK,
                    foundSeq = error.foundSeq,
                    expectedSeq = error.expectedSeq,
                )
                is SyncError.WrongPassphrase -> StoredResult(STOPPED, error = WRONG_PASSPHRASE, keyId = error.keyId)
                is SyncError.Corrupt -> StoredResult(STOPPED, error = CORRUPT, message = error.detail)
                SyncError.RemotePlaintext -> StoredResult(STOPPED, error = REMOTE_PLAINTEXT)
            }
            SyncLastResult.Contended -> StoredResult(CONTENDED)
            SyncLastResult.NeedsSignIn -> StoredResult(NEEDS_SIGN_IN)
            is SyncLastResult.Failed -> StoredResult(FAILED, message = result.message)
        }
    }
}
