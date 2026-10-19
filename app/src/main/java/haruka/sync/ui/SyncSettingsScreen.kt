package haruka.sync.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Binds
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.widget.BasePreferenceWidget
import eu.kanade.presentation.more.settings.widget.PreferenceGroupHeader
import eu.kanade.presentation.more.settings.widget.PrefsHorizontalPadding
import eu.kanade.presentation.more.settings.widget.SwitchPreferenceWidget
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import eu.kanade.presentation.util.Screen
import eu.kanade.presentation.util.relativeTimeSpanString
import haruka.i18n.HR
import haruka.motion.AnimationProfile
import haruka.motion.LocalAnimationProfile
import haruka.sync.SyncManager
import haruka.sync.remote.SyncError
import haruka.sync.store.RemovalOrigin
import kotlinx.coroutines.flow.receiveAsFlow
import mihon.app.di.appGraph
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Error
import mihon.icons.materialsymbols.roundedfilled.Visibility
import mihon.icons.materialsymbols.roundedfilled.VisibilityOff
import mihon.icons.materialsymbols.roundedfilled.Warning
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.TextButton
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.secondaryItemAlpha

/**
 * Binds [SyncManager] (the sync side's app singleton) as the screen's [SyncSettingsModel] and exposes it for
 * [syncSettingsPreference]; Metro merges this into upstream's `AppGraph`.
 */
@ContributesTo(AppScope::class)
interface SyncUiGraph {
    val syncSettingsModel: SyncSettingsModel

    @Binds
    val SyncManager.bindSyncSettingsModel: SyncSettingsModel
}

/** Settings > Data and storage group that opens [SyncSettingsScreen], with the sync status as its subtitle. */
@Composable
fun syncSettingsPreference(): Preference.PreferenceGroup {
    val navigator = LocalNavigator.currentOrThrow
    val context = LocalContext.current
    val model = remember { (context.appGraph as SyncUiGraph).syncSettingsModel }
    val state by model.state.collectAsStateWithLifecycle()
    return Preference.PreferenceGroup(
        title = stringResource(HR.strings.sync_title),
        preferenceItems = listOf(
            Preference.PreferenceItem.TextPreference(
                title = stringResource(HR.strings.sync_google_drive),
                subtitle = entrySummary(state),
                onClick = { navigator.push(SyncSettingsScreen()) },
            ),
        ),
    )
}

@Composable
private fun entrySummary(state: SyncSettingsState): String = when {
    state.account == null -> stringResource(HR.strings.sync_status_signed_out)
    state.needsAttention() -> stringResource(HR.strings.sync_status_attention)
    else -> lastSyncedText(state.lastSyncAtMillis)
}

private fun SyncSettingsState.needsAttention(): Boolean = pendingRemovals.isNotEmpty() ||
    encryption == EncryptionStatus.LOCKED ||
    lastResult is SyncLastResult.Stopped ||
    lastResult is SyncLastResult.NeedsSignIn

/**
 * Google Drive sync: account, the master switch with Sync now and the last result, held mass removals, the
 * automatic schedule and its restrictions, and end-to-end encryption.
 */
class SyncSettingsScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val viewModel = metroViewModel<SyncSettingsViewModel>()
        val state by viewModel.state.collectAsStateWithLifecycle()
        val ui by viewModel.ui.collectAsStateWithLifecycle()
        var confirmSignOut by rememberSaveable { mutableStateOf(false) }

        val consent = rememberLauncherForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult(),
            viewModel::onConsentResult,
        )
        LaunchedEffect(viewModel) {
            viewModel.consentRequests.receiveAsFlow().collect {
                consent.launch(IntentSenderRequest.Builder(it).build())
            }
        }

        val signedIn = state.account != null
        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(HR.strings.sync_title),
                    navigateUp = navigator::pop,
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { contentPadding ->
            LazyColumn(contentPadding = contentPadding) {
                item(key = "account-header") { GroupHeader(stringResource(HR.strings.sync_google_drive)) }
                item(key = "account") {
                    val account = state.account
                    if (account == null) {
                        SignedOut(ui.signingIn, ui.signInError, onSignIn = viewModel::signIn)
                    } else {
                        TextPreferenceWidget(
                            title = account,
                            subtitle = stringResource(HR.strings.sync_signed_in_summary),
                            widget = {
                                OutlinedButton(
                                    onClick = { confirmSignOut = true },
                                    modifier = Modifier.padding(start = 16.dp),
                                ) {
                                    Text(text = stringResource(HR.strings.sync_sign_out))
                                }
                            },
                        )
                    }
                }

                if (state.pendingRemovals.isNotEmpty()) {
                    item(key = "removals") {
                        HeldRemovalsCard(
                            removals = state.pendingRemovals,
                            onConfirm = viewModel::confirmRemovals,
                            onReject = viewModel::rejectRemovals,
                        )
                    }
                }

                item(key = "sync-header") { GroupHeader(stringResource(HR.strings.sync_title)) }
                item(key = "status") {
                    SyncStatus(
                        state = state,
                        canSync = signedIn,
                        onSyncNow = viewModel::syncNow,
                        onUploadThisLibrary = viewModel::uploadThisLibrary,
                    )
                }

                item(key = "schedule-header") { GroupHeader(stringResource(HR.strings.sync_schedule)) }
                item(key = "enabled") {
                    SwitchPreferenceWidget(
                        title = stringResource(HR.strings.sync_enable),
                        subtitle = stringResource(HR.strings.sync_enable_summary),
                        checked = state.enabled,
                        onCheckedChanged = viewModel::setEnabled,
                    )
                }
                item(key = "interval") { IntervalButtons(state.interval, viewModel::setInterval) }
                item(key = "wifi") {
                    SwitchPreferenceWidget(
                        title = stringResource(MR.strings.connected_to_wifi),
                        checked = state.wifiOnly,
                        onCheckedChanged = viewModel::setWifiOnly,
                    )
                }
                item(key = "charging") {
                    SwitchPreferenceWidget(
                        title = stringResource(MR.strings.charging),
                        checked = state.chargingOnly,
                        onCheckedChanged = viewModel::setChargingOnly,
                    )
                }
                item(key = "restrictions-note") { Note(stringResource(HR.strings.sync_restrictions_summary)) }

                item(key = "encryption-header") { GroupHeader(stringResource(HR.strings.sync_encryption)) }
                item(key = "encryption") {
                    EncryptionItem(state.encryption, enabled = signedIn, onOpen = viewModel::openPassphraseDialog)
                }
            }
        }

        if (confirmSignOut) {
            AlertDialog(
                onDismissRequest = { confirmSignOut = false },
                title = { Text(text = stringResource(HR.strings.sync_sign_out_title)) },
                text = { Text(text = stringResource(HR.strings.sync_sign_out_message)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            confirmSignOut = false
                            viewModel.signOut()
                        },
                    ) {
                        Text(text = stringResource(HR.strings.sync_sign_out))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { confirmSignOut = false }) {
                        Text(text = stringResource(MR.strings.action_cancel))
                    }
                },
            )
        }

        ui.passphraseDialog?.let { mode ->
            PassphraseDialog(
                mode = mode,
                busy = ui.passphraseBusy,
                error = ui.passphraseError,
                onDismissRequest = viewModel::dismissPassphraseDialog,
                onSubmit = viewModel::submitPassphrase,
            )
        }
    }
}

@Composable
private fun GroupHeader(title: String) {
    Column(modifier = Modifier.semantics { heading() }) {
        PreferenceGroupHeader(title)
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier
            .padding(horizontal = PrefsHorizontalPadding, vertical = 4.dp)
            .secondaryItemAlpha(),
    )
}

@Composable
private fun SignedOut(signingIn: Boolean, error: String?, onSignIn: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PrefsHorizontalPadding, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(HR.strings.sync_drive_info),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onSignIn, enabled = !signingIn) {
            Text(text = stringResource(if (signingIn) HR.strings.sync_signing_in else HR.strings.sync_sign_in))
        }
        if (error != null) ErrorLine(stringResource(HR.strings.sync_sign_in_failed, error))
    }
}

/** An error with a non-colour cue (icon) and announced politely when it appears. */
@Composable
private fun ErrorLine(text: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    ) {
        Icon(
            imageVector = MaterialSymbols.Rounded.Error,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(20.dp),
        )
        Text(text = text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun lastSyncedText(lastSyncAtMillis: Long?): String = if (lastSyncAtMillis == null) {
    stringResource(HR.strings.sync_status_never)
} else {
    stringResource(HR.strings.sync_last_synced, relativeTimeSpanString(lastSyncAtMillis))
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SyncStatus(
    state: SyncSettingsState,
    canSync: Boolean,
    onSyncNow: () -> Unit,
    onUploadThisLibrary: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PrefsHorizontalPadding, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = if (state.running) {
                stringResource(
                    HR.strings.sync_running,
                )
            } else {
                lastSyncedText(state.lastSyncAtMillis)
            },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        if (state.running) {
            // REDUCED uses a flat bar because the travelling wave is decorative motion.
            if (LocalAnimationProfile.current == AnimationProfile.REDUCED) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
        // While signed out the account section is the call to action; a stale result would only repeat it.
        when (val result = state.lastResult.takeIf { canSync }) {
            is SyncLastResult.Synced -> if (result.conflicts > 0) {
                Text(
                    text = pluralStringResource(HR.plurals.sync_conflicts, result.conflicts, result.conflicts),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.secondaryItemAlpha(),
                )
            }
            null -> Unit
            else -> ErrorLine(resultMessage(result))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onSyncNow, enabled = canSync && !state.running) {
                Text(text = stringResource(HR.strings.sync_now))
            }
            val error = (state.lastResult as? SyncLastResult.Stopped)?.error
            if (error is SyncError.RemoteRolledBack) {
                FilledTonalButton(onClick = onUploadThisLibrary, enabled = canSync && !state.running) {
                    Text(text = stringResource(HR.strings.sync_upload_library))
                }
            }
        }
    }
}

/** What went wrong and how to get past it; [SyncLastResult.Synced] never reaches here. */
@Composable
private fun resultMessage(result: SyncLastResult): String = when (result) {
    is SyncLastResult.Synced -> ""
    SyncLastResult.Contended -> stringResource(HR.strings.sync_error_contended)
    SyncLastResult.NeedsSignIn -> stringResource(HR.strings.sync_error_needs_sign_in)
    is SyncLastResult.Failed -> stringResource(HR.strings.sync_error_failed, result.message)
    is SyncLastResult.Stopped -> when (val error = result.error) {
        is SyncError.FormatTooNew -> stringResource(HR.strings.sync_error_format_too_new)
        is SyncError.NeedsPassphrase -> stringResource(HR.strings.sync_error_needs_passphrase)
        is SyncError.RemoteRolledBack -> stringResource(HR.strings.sync_error_rolled_back)
        is SyncError.WrongPassphrase -> stringResource(HR.strings.sync_error_wrong_passphrase)
        is SyncError.Corrupt -> stringResource(HR.strings.sync_error_corrupt, error.detail)
        SyncError.RemotePlaintext -> stringResource(HR.strings.sync_error_remote_plaintext)
    }
}

/**
 * The mass-removal guard's held entries, by where they were removed, named by title. Confirm and reject act on
 * the whole set, as the engine does.
 */
@Composable
private fun HeldRemovalsCard(removals: List<HeldRemoval>, onConfirm: () -> Unit, onReject: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(imageVector = MaterialSymbols.RoundedFilled.Warning, contentDescription = null)
                Text(
                    text = stringResource(HR.strings.sync_removals_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() },
                )
            }
            RemovalOrigin.entries.forEach { origin ->
                val titles = removals.filter { it.origin == origin }.map { it.title }
                if (titles.isNotEmpty()) RemovalGroup(origin, titles)
            }
            Text(text = stringResource(HR.strings.sync_removals_message), style = MaterialTheme.typography.bodyMedium)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedButton(onClick = onConfirm) {
                    Text(text = stringResource(HR.strings.sync_removals_confirm))
                }
                Button(onClick = onReject) {
                    Text(text = stringResource(HR.strings.sync_removals_reject))
                }
            }
        }
    }
}

@Composable
private fun RemovalGroup(origin: RemovalOrigin, titles: List<String>) {
    val summary = when (origin) {
        RemovalOrigin.LOCAL -> HR.plurals.sync_removals_local
        RemovalOrigin.REMOTE -> HR.plurals.sync_removals_remote
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = pluralStringResource(summary, titles.size, titles.size),
            style = MaterialTheme.typography.bodyMedium,
        )
        titles.take(MAX_LISTED_TITLES).forEach { title ->
            Text(
                text = "• $title",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val more = titles.size - MAX_LISTED_TITLES
        if (more > 0) {
            Text(
                text = pluralStringResource(HR.plurals.sync_removals_more, more, more),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private const val MAX_LISTED_TITLES = 8

/**
 * The periodic interval as one connected single-choice button group (the M3E pattern of Settings > Appearance >
 * Animations), laid out as a preference row so it lines up with the switches around it.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun IntervalButtons(selected: SyncInterval, onSelect: (SyncInterval) -> Unit) {
    val options = SyncInterval.entries
    BasePreferenceWidget(
        title = stringResource(HR.strings.sync_interval),
        subcomponent = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PrefsHorizontalPadding, vertical = 8.dp)
                    .selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
            ) {
                options.forEachIndexed { index, interval ->
                    val description = intervalSummary(interval)
                    ToggleButton(
                        checked = selected == interval,
                        onCheckedChange = { onSelect(interval) },
                        shapes = when (index) {
                            0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                            options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                            else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                        },
                        // Six buttons share a phone's width; the default 16dp side padding clips "24 h".
                        contentPadding = PaddingValues(horizontal = 4.dp),
                        modifier = Modifier
                            .weight(1f)
                            .semantics {
                                role = Role.RadioButton
                                contentDescription = description
                            },
                    ) {
                        Text(text = intervalLabel(interval), maxLines = 1, softWrap = false)
                    }
                }
            }
            Text(
                text = intervalSummary(selected),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .padding(horizontal = PrefsHorizontalPadding)
                    .secondaryItemAlpha(),
            )
        },
    )
}

@Composable
private fun intervalLabel(interval: SyncInterval): String = if (interval == SyncInterval.OFF) {
    stringResource(HR.strings.sync_interval_off)
} else {
    pluralStringResource(HR.plurals.sync_interval_short, interval.hours, interval.hours)
}

@Composable
private fun intervalSummary(interval: SyncInterval): String = if (interval == SyncInterval.OFF) {
    stringResource(HR.strings.sync_interval_off_summary)
} else {
    pluralStringResource(HR.plurals.sync_interval_summary, interval.hours, interval.hours)
}

@Composable
private fun EncryptionItem(status: EncryptionStatus, enabled: Boolean, onOpen: (PassphraseMode) -> Unit) {
    val (summary, action, mode) = when (status) {
        EncryptionStatus.OFF -> Triple(
            HR.strings.sync_encryption_off,
            HR.strings.sync_set_passphrase,
            PassphraseMode.SET,
        )
        EncryptionStatus.ON -> Triple(
            HR.strings.sync_encryption_on,
            HR.strings.sync_change_passphrase,
            PassphraseMode.CHANGE,
        )
        EncryptionStatus.LOCKED -> Triple(
            HR.strings.sync_encryption_locked,
            HR.strings.sync_enter_passphrase,
            PassphraseMode.UNLOCK,
        )
    }
    TextPreferenceWidget(
        title = stringResource(HR.strings.sync_encryption_title),
        subtitle = stringResource(summary),
        widget = {
            OutlinedButton(
                onClick = { onOpen(mode) },
                enabled = enabled,
                modifier = Modifier.padding(start = 16.dp),
            ) {
                Text(text = stringResource(action))
            }
        },
    )
}

/**
 * Passphrase entry. Setting or changing one asks twice and warns that a lost passphrase can't be recovered;
 * unlocking asks once. The typed text is kept only in memory, never in saved instance state.
 */
@Composable
private fun PassphraseDialog(
    mode: PassphraseMode,
    busy: Boolean,
    error: PassphraseResult?,
    onDismissRequest: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    val passphrase = rememberTextFieldState()
    val repeat = rememberTextFieldState()
    var hidden by remember { mutableStateOf(true) }
    val creating = mode != PassphraseMode.UNLOCK
    val mismatch = creating && repeat.text.isNotEmpty() && repeat.text.toString() != passphrase.text.toString()
    val canSubmit = !busy && passphrase.text.isNotEmpty() &&
        (!creating || repeat.text.toString() == passphrase.text.toString())
    val (title, confirm) = when (mode) {
        PassphraseMode.SET -> HR.strings.sync_set_passphrase to HR.strings.sync_encrypt
        PassphraseMode.CHANGE -> HR.strings.sync_change_passphrase to HR.strings.sync_change
        PassphraseMode.UNLOCK -> HR.strings.sync_enter_passphrase to HR.strings.sync_unlock
    }
    val errorText = when (error) {
        null, PassphraseResult.Done -> null
        PassphraseResult.WrongPassphrase -> stringResource(HR.strings.sync_passphrase_wrong)
        PassphraseResult.NotEncrypted -> stringResource(HR.strings.sync_passphrase_not_encrypted)
        is PassphraseResult.Failed -> stringResource(HR.strings.sync_passphrase_failed, error.message)
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(text = stringResource(title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (creating) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(
                            imageVector = MaterialSymbols.RoundedFilled.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            text = stringResource(HR.strings.sync_passphrase_warning),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (mode == PassphraseMode.CHANGE) {
                        Text(
                            text = stringResource(HR.strings.sync_passphrase_change_info),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                PassphraseField(
                    state = passphrase,
                    label = stringResource(HR.strings.sync_passphrase),
                    hidden = hidden,
                    onToggleHidden = { hidden = !hidden },
                    isError = errorText != null,
                    supportingText = if (creating) null else errorText,
                    imeAction = if (creating) ImeAction.Next else ImeAction.Done,
                    enabled = !busy,
                )
                if (creating) {
                    PassphraseField(
                        state = repeat,
                        label = stringResource(HR.strings.sync_passphrase_repeat),
                        hidden = hidden,
                        onToggleHidden = { hidden = !hidden },
                        isError = mismatch || errorText != null,
                        supportingText = if (mismatch) {
                            stringResource(
                                HR.strings.sync_passphrase_mismatch,
                            )
                        } else {
                            errorText
                        },
                        imeAction = ImeAction.Done,
                        enabled = !busy,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(passphrase.text.toString()) }, enabled = canSubmit) {
                Text(text = stringResource(if (busy) HR.strings.sync_passphrase_working else confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest, enabled = !busy) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
    )
}

@Composable
private fun PassphraseField(
    state: TextFieldState,
    label: String,
    hidden: Boolean,
    onToggleHidden: () -> Unit,
    isError: Boolean,
    supportingText: String?,
    imeAction: ImeAction,
    enabled: Boolean,
) {
    OutlinedSecureTextField(
        state = state,
        label = { Text(text = label) },
        enabled = enabled,
        trailingIcon = {
            IconButton(onClick = onToggleHidden) {
                Icon(
                    imageVector = if (hidden) {
                        MaterialSymbols.RoundedFilled.Visibility
                    } else {
                        MaterialSymbols.RoundedFilled.VisibilityOff
                    },
                    contentDescription = stringResource(
                        if (hidden) HR.strings.sync_show_passphrase else HR.strings.sync_hide_passphrase,
                    ),
                )
            }
        },
        textObfuscationMode = if (hidden) TextObfuscationMode.Hidden else TextObfuscationMode.Visible,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = imeAction),
        isError = isError,
        supportingText = supportingText?.let { { Text(text = it) } },
        modifier = Modifier.fillMaxWidth(),
    )
}
