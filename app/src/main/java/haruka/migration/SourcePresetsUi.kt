package haruka.migration

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.presentation.components.AppBar
import haruka.di.harukaGraph
import haruka.i18n.HR
import mihon.feature.migration.config.MigrationConfigScreen.MigrationSource
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Delete
import mihon.icons.materialsymbols.rounded.Edit
import mihon.icons.materialsymbols.rounded.FormatListNumbered
import mihon.icons.materialsymbols.rounded.Save
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.theme.header
import tachiyomi.presentation.core.util.collectAsState
import tachiyomi.presentation.core.util.secondaryItemAlpha

/**
 * Toolbar action of the migration source screen that opens the source presets sheet. [sources] is the screen's
 * list (selected first, in order); [onApply] must select exactly the given ids in that order through the screen's
 * own selection so its Continue path saves them as a manual selection would.
 */
@Composable
fun sourcePresetsAction(sources: List<MigrationSource>, onApply: (List<Long>) -> Unit): AppBar.Action {
    var open by rememberSaveable { mutableStateOf(false) }
    if (open) {
        SourcePresetsSheet(
            sources = sources,
            onApply = {
                open = false
                onApply(it)
            },
            onDismissRequest = { open = false },
        )
    }
    return AppBar.Action(
        title = stringResource(HR.strings.migration_presets),
        icon = MaterialSymbols.Rounded.FormatListNumbered,
        onClick = { open = true },
    )
}

private sealed interface PresetDialog {
    data object Save : PresetDialog
    data class ConfirmReplace(val name: String) : PresetDialog
    data class Rename(val name: String) : PresetDialog
    data class Delete(val name: String) : PresetDialog
}

@Composable
private fun SourcePresetsSheet(
    sources: List<MigrationSource>,
    onApply: (List<Long>) -> Unit,
    onDismissRequest: () -> Unit,
) {
    val context = LocalContext.current
    val preference = remember { context.harukaGraph.harukaPreferences.migrationSourcePresets }
    val presets by preference.collectAsState()
    val names = remember(sources) { sources.associate { it.id to it.name } }
    val selected = remember(sources) { sources.filter { it.isSelected }.map { it.id } }
    var dialog by remember { mutableStateOf<PresetDialog?>(null) }

    AdaptiveSheet(onDismissRequest = onDismissRequest) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(vertical = MaterialTheme.padding.medium),
        ) {
            Text(
                text = stringResource(HR.strings.migration_presets),
                style = MaterialTheme.typography.header,
                modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium),
            )
            if (presets.isEmpty()) {
                Text(
                    text = stringResource(HR.strings.migration_presets_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .padding(horizontal = MaterialTheme.padding.medium, vertical = MaterialTheme.padding.small)
                        .secondaryItemAlpha(),
                )
            }
            presets.forEach { preset ->
                val application = SourcePresets.apply(preset, names.keys)
                PresetRow(
                    preset = preset,
                    sourceNames = application.selected.mapNotNull(names::get),
                    missing = application.missing.size,
                    onClick = { onApply(application.selected) },
                    onRename = { dialog = PresetDialog.Rename(preset.name) },
                    onDelete = { dialog = PresetDialog.Delete(preset.name) },
                )
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = MaterialTheme.padding.small))
            ListItem(
                headlineContent = { Text(stringResource(HR.strings.migration_presets_save)) },
                leadingContent = { Icon(MaterialSymbols.Rounded.Save, contentDescription = null) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier
                    .clickable(enabled = selected.isNotEmpty()) { dialog = PresetDialog.Save }
                    .then(if (selected.isEmpty()) Modifier.secondaryItemAlpha() else Modifier),
            )
        }
    }

    when (val current = dialog) {
        PresetDialog.Save -> PresetNameDialog(
            title = stringResource(HR.strings.migration_presets_save),
            initialName = "",
            confirmText = stringResource(MR.strings.action_save),
            isTaken = { false },
            onConfirm = { name ->
                dialog = if (SourcePresets.find(presets, name) != null) {
                    PresetDialog.ConfirmReplace(name)
                } else {
                    preference.set(SourcePresets.save(presets, name, selected))
                    null
                }
            },
            onDismissRequest = { dialog = null },
        )
        is PresetDialog.ConfirmReplace -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(stringResource(HR.strings.migration_presets_replace_title)) },
            text = { Text(stringResource(HR.strings.migration_presets_replace, current.name.trim())) },
            confirmButton = {
                TextButton(
                    onClick = {
                        preference.set(SourcePresets.save(presets, current.name, selected))
                        dialog = null
                    },
                ) { Text(stringResource(HR.strings.migration_presets_replace_action)) }
            },
            dismissButton = {
                TextButton(onClick = { dialog = null }) { Text(stringResource(MR.strings.action_cancel)) }
            },
        )
        is PresetDialog.Rename -> PresetNameDialog(
            title = stringResource(HR.strings.migration_presets_rename),
            initialName = current.name,
            confirmText = stringResource(HR.strings.migration_presets_rename_action),
            isTaken = { name ->
                presets.any { SourcePresets.sameName(it.name, name) && !SourcePresets.sameName(it.name, current.name) }
            },
            onConfirm = { name ->
                preference.set(SourcePresets.rename(presets, current.name, name))
                dialog = null
            },
            onDismissRequest = { dialog = null },
        )
        is PresetDialog.Delete -> AlertDialog(
            onDismissRequest = { dialog = null },
            text = { Text(stringResource(HR.strings.migration_presets_delete, current.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        preference.set(SourcePresets.delete(presets, current.name))
                        dialog = null
                    },
                ) { Text(stringResource(MR.strings.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { dialog = null }) { Text(stringResource(MR.strings.action_cancel)) }
            },
        )
        null -> Unit
    }
}

@Composable
private fun PresetRow(
    preset: SourcePreset,
    sourceNames: List<String>,
    missing: Int,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(preset.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                if (sourceNames.isNotEmpty()) {
                    Text(sourceNames.joinToString(" › "), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (missing > 0) {
                    Text(
                        text = stringResource(HR.strings.migration_presets_not_installed, missing),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        trailingContent = {
            Row {
                IconButton(onClick = onRename) {
                    Icon(MaterialSymbols.Rounded.Edit, contentDescription = stringResource(MR.strings.action_edit))
                }
                IconButton(onClick = onDelete) {
                    Icon(MaterialSymbols.Rounded.Delete, contentDescription = stringResource(MR.strings.action_delete))
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun PresetNameDialog(
    title: String,
    initialName: String,
    confirmText: String,
    isTaken: (String) -> Boolean,
    onConfirm: (String) -> Unit,
    onDismissRequest: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    val taken = isTaken(name)
    val valid = name.isNotBlank() && !taken
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(HR.strings.migration_presets_name)) },
                singleLine = true,
                isError = taken,
                supportingText = if (taken) {
                    { Text(stringResource(HR.strings.migration_presets_name_taken)) }
                } else {
                    null
                },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Done,
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = valid) { Text(confirmText) }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) { Text(stringResource(MR.strings.action_cancel)) }
        },
    )
}
