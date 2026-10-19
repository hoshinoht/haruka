package haruka.sync.ui

import android.app.Activity
import android.app.PendingIntent
import androidx.activity.result.ActivityResult
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Screen-side state of [SyncSettingsScreen] that has to outlive a configuration change: an in-flight sign-in or
 * passphrase derivation must not be cancelled by a rotation.
 */
@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class SyncSettingsViewModel(private val model: SyncSettingsModel) : ViewModel() {

    val state: StateFlow<SyncSettingsState> = model.state

    private val mutableUi = MutableStateFlow(SyncSettingsUiState())
    val ui: StateFlow<SyncSettingsUiState> = mutableUi.asStateFlow()

    /** Consent flows for the screen to launch; buffered so a request made while it is stopped is not lost. */
    val consentRequests = Channel<PendingIntent>(Channel.BUFFERED)

    fun signIn() {
        if (mutableUi.value.signingIn) return
        mutableUi.update { it.copy(signingIn = true, signInError = null) }
        viewModelScope.launch { handle(model.signIn()) }
    }

    fun onConsentResult(result: ActivityResult) {
        if (result.resultCode != Activity.RESULT_OK) {
            // Backing out of Google's account picker is a choice, not an error.
            mutableUi.update { it.copy(signingIn = false) }
            return
        }
        viewModelScope.launch { handle(model.completeSignIn(result.data)) }
    }

    fun signOut() {
        viewModelScope.launch { model.signOut() }
    }

    fun setEnabled(enabled: Boolean) = model.setEnabled(enabled)

    fun setInterval(interval: SyncInterval) = model.setInterval(interval)

    fun setWifiOnly(wifiOnly: Boolean) = model.setWifiOnly(wifiOnly)

    fun setChargingOnly(chargingOnly: Boolean) = model.setChargingOnly(chargingOnly)

    fun syncNow() = model.syncNow()

    fun uploadThisLibrary() = model.uploadThisLibrary()

    fun confirmRemovals() = model.confirmRemovals()

    fun rejectRemovals() = model.rejectRemovals()

    fun openPassphraseDialog(mode: PassphraseMode) {
        mutableUi.update { it.copy(passphraseDialog = mode, passphraseError = null) }
    }

    fun dismissPassphraseDialog() {
        // Closing mid-derivation would hide whether the remote was re-encrypted; the dialog stays until it ends.
        if (mutableUi.value.passphraseBusy) return
        mutableUi.update { it.copy(passphraseDialog = null, passphraseError = null) }
    }

    fun submitPassphrase(passphrase: String) {
        val mode = mutableUi.value.passphraseDialog ?: return
        if (mutableUi.value.passphraseBusy) return
        mutableUi.update { it.copy(passphraseBusy = true, passphraseError = null) }
        viewModelScope.launch {
            val result = when (mode) {
                PassphraseMode.SET, PassphraseMode.CHANGE -> model.setPassphrase(passphrase)
                PassphraseMode.UNLOCK -> model.unlock(passphrase)
            }
            mutableUi.update {
                if (result == PassphraseResult.Done) {
                    it.copy(passphraseBusy = false, passphraseDialog = null)
                } else {
                    it.copy(passphraseBusy = false, passphraseError = result)
                }
            }
        }
    }

    private suspend fun handle(result: SignInResult) {
        when (result) {
            is SignInResult.SignedIn -> mutableUi.update { it.copy(signingIn = false) }
            is SignInResult.NeedsUserAction -> consentRequests.send(result.pendingIntent)
            is SignInResult.Failed -> mutableUi.update { it.copy(signingIn = false, signInError = result.message) }
        }
    }
}

data class SyncSettingsUiState(
    val signingIn: Boolean = false,
    val signInError: String? = null,
    val passphraseDialog: PassphraseMode? = null,
    val passphraseBusy: Boolean = false,
    val passphraseError: PassphraseResult? = null,
)

enum class PassphraseMode { SET, CHANGE, UNLOCK }
