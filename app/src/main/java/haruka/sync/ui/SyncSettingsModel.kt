package haruka.sync.ui

import android.app.PendingIntent
import android.content.Intent
import haruka.sync.model.EntryKey
import haruka.sync.remote.SyncError
import haruka.sync.store.RemovalOrigin
import kotlinx.coroutines.flow.StateFlow

/**
 * Everything the Sync settings screen reads and does. The screen talks to the sync side only through this
 * interface; the implementation owns persistence, scheduling, the Drive grant and the engine.
 */
interface SyncSettingsModel {

    val state: StateFlow<SyncSettingsState>

    /** Asks for `drive.appdata` without UI; Google's account picker comes back as [SignInResult.NeedsUserAction]. */
    suspend fun signIn(): SignInResult

    /** Finishes the consent flow the screen launched for [SignInResult.NeedsUserAction] with its result intent. */
    suspend fun completeSignIn(data: Intent?): SignInResult

    /**
     * Revokes the Drive grant, cancels scheduled syncs and forgets this device's sync state (base, held removals,
     * key). The library on this device and the copy in Drive stay as they are.
     */
    suspend fun signOut()

    /** Automatic syncs (after reading, app lifecycle, periodic); "Sync now" runs either way. */
    fun setEnabled(enabled: Boolean)

    fun setInterval(interval: SyncInterval)

    fun setWifiOnly(wifiOnly: Boolean)

    fun setChargingOnly(chargingOnly: Boolean)

    /** Starts a sync right away, whether or not automatic syncs are on, ignoring the Wi-Fi and charging options. */
    fun syncNow()

    /**
     * Recovery from [SyncError.RemoteRolledBack]: merges this device's library with whatever Drive holds, without
     * removing anything, and commits it as a fresh start every other device merges the same way.
     */
    fun uploadThisLibrary()

    /**
     * Encrypts the library in Drive with a key derived from [passphrase] (first time, or a new passphrase while
     * already encrypted). Other devices then need the same passphrase.
     */
    suspend fun setPassphrase(passphrase: String): PassphraseResult

    /** Unlocks a library another device encrypted, so this device can sync it. */
    suspend fun unlock(passphrase: String): PassphraseResult

    /** Applies every held removal on the next sync, on this device and in Drive. */
    fun confirmRemovals()

    /** Keeps every held entry: the next sync puts them back in the library everywhere. */
    fun rejectRemovals()
}

data class SyncSettingsState(
    /** The signed-in Google account; null while signed out. */
    val account: String? = null,
    /** Automatic syncs are on. */
    val enabled: Boolean = false,
    val interval: SyncInterval = SyncInterval.DEFAULT,
    val wifiOnly: Boolean = false,
    val chargingOnly: Boolean = false,
    val running: Boolean = false,
    val lastSyncAtMillis: Long? = null,
    val lastResult: SyncLastResult? = null,
    val encryption: EncryptionStatus = EncryptionStatus.OFF,
    /** Removals the mass-removal guard holds until the user confirms or rejects them, as one set. */
    val pendingRemovals: List<HeldRemoval> = emptyList(),
)

/** How often automatic syncs run; [OFF] leaves only manual syncs and the app's own triggers. */
enum class SyncInterval(val hours: Int) {
    OFF(0),
    HOURS_1(1),
    HOURS_3(3),
    HOURS_6(6),
    HOURS_12(12),
    HOURS_24(24),
    ;

    companion object {
        val DEFAULT = HOURS_6
    }
}

/** Outcome of the last sync attempt on this device. */
sealed interface SyncLastResult {

    data class Synced(
        /** Union merge: nothing was removed because this device had no shared history with Drive. */
        val union: Boolean,
        /** Settings both sides changed; this device's value was kept. */
        val conflicts: Int,
    ) : SyncLastResult

    /** The engine refused to go on until the user (or an app update) acts. */
    data class Stopped(val error: SyncError) : SyncLastResult

    /** Other devices kept committing first; a retry is scheduled. */
    data object Contended : SyncLastResult

    /** The Google grant was revoked or expired; only signing in again continues. */
    data object NeedsSignIn : SyncLastResult

    /** Network, Drive or local failure; [message] is the exception text for troubleshooting. */
    data class Failed(val message: String) : SyncLastResult
}

enum class EncryptionStatus {
    /** The library in Drive is plaintext. */
    OFF,

    /** Encrypted, and this device holds the key. */
    ON,

    /** Encrypted by another device; this device needs the passphrase before it can sync. */
    LOCKED,
}

/** One entry the mass-removal guard held back; [title] is what the user recognises it by. */
data class HeldRemoval(
    val key: EntryKey,
    val title: String,
    /** [RemovalOrigin.LOCAL]: removed on this device; [RemovalOrigin.REMOTE]: removed on another device. */
    val origin: RemovalOrigin,
)

sealed interface SignInResult {
    data class SignedIn(val account: String) : SignInResult

    /** The screen launches [pendingIntent] and passes the result to [SyncSettingsModel.completeSignIn]. */
    data class NeedsUserAction(val pendingIntent: PendingIntent) : SignInResult

    /** Play services is missing or refused; [message] is shown as is. */
    data class Failed(val message: String) : SignInResult
}

sealed interface PassphraseResult {
    data object Done : PassphraseResult

    data object WrongPassphrase : PassphraseResult

    /** [SyncSettingsModel.unlock] on a library that isn't encrypted (or has never been synced). */
    data object NotEncrypted : PassphraseResult

    data class Failed(val message: String) : PassphraseResult
}
