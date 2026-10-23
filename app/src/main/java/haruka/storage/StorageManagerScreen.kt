package haruka.storage

import android.text.format.Formatter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.widget.PreferenceGroupHeader
import eu.kanade.presentation.more.settings.widget.PrefsHorizontalPadding
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import eu.kanade.presentation.util.Screen
import haruka.i18n.HR
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Delete
import mihon.icons.materialsymbols.rounded.KeyboardArrowDown
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.LabeledCheckbox
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.TextButton
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.secondaryItemAlpha

/** Settings > Data and storage > Storage usage item opening [StorageManagerScreen]. */
@Composable
fun downloadsStoragePreference(): Preference.PreferenceItem.TextPreference {
    val navigator = LocalNavigator.currentOrThrow
    return Preference.PreferenceItem.TextPreference(
        title = stringResource(HR.strings.storage_title),
        subtitle = stringResource(HR.strings.storage_entry_summary),
        onClick = { navigator.push(StorageManagerScreen()) },
    )
}

/** What the downloads take, by source and series, with cleanups that delete through the download manager. */
class StorageManagerScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val viewModel = metroViewModel<StorageManagerViewModel>()
        val state by viewModel.state.collectAsStateWithLifecycle()
        var dialog by remember { mutableStateOf<Dialog?>(null) }

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(HR.strings.storage_title),
                    navigateUp = navigator::pop,
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { contentPadding ->
            val summary = state.summary
            when {
                state.phase == StorageManagerViewModel.Phase.FAILED -> Column(Modifier.padding(contentPadding)) {
                    Note(stringResource(HR.strings.storage_failed))
                }
                state.phase != StorageManagerViewModel.Phase.READY || summary == null ->
                    Progress(state, Modifier.padding(contentPadding))
                else -> LazyColumn(contentPadding = contentPadding) {
                    content(
                        summary,
                        onDialog = { dialog = it },
                        onDeleteRead = {
                            viewModel.prepareReadChapters()
                            dialog = Dialog.DeleteRead
                        },
                    )
                }
            }
        }

        when (val current = dialog) {
            null -> {}
            is Dialog.DeleteSeries -> ConfirmDialog(
                title = stringResource(HR.strings.storage_delete_series_title),
                message = stringResource(
                    HR.strings.storage_delete_series_message,
                    current.series.folder.name,
                    formatSize(current.series.size),
                ),
                onDismiss = { dialog = null },
                onConfirm = { viewModel.deleteSeries(current.series) },
            )
            is Dialog.RemoveNotInLibrary -> ConfirmDialog(
                title = stringResource(HR.strings.storage_remove_not_in_library),
                message = pluralStringResource(
                    HR.plurals.storage_delete_series_confirm,
                    current.series.size,
                    current.series.size,
                    formatSize(current.series.sumOf { it.size }),
                ),
                onDismiss = { dialog = null },
                onConfirm = viewModel::removeNotInLibrary,
            )
            Dialog.DeleteRead -> DeleteReadDialog(
                totals = state.readChapters,
                onDismiss = { dialog = null },
                onConfirm = viewModel::deleteReadChapters,
            )
        }
    }

    private fun LazyListScope.content(
        summary: StorageSummary,
        onDialog: (Dialog) -> Unit,
        onDeleteRead: () -> Unit,
    ) {
        item(key = "total") {
            TextPreferenceWidget(
                title = stringResource(HR.strings.storage_total),
                subtitle = if (summary.seriesCount == 0) {
                    stringResource(HR.strings.storage_empty)
                } else {
                    pluralStringResource(
                        HR.plurals.storage_series_size,
                        summary.seriesCount,
                        summary.seriesCount,
                        formatSize(summary.total),
                    )
                },
            )
        }
        if (summary.seriesCount == 0) return

        item(key = "cleanup-header") { GroupHeader(stringResource(HR.strings.storage_cleanup)) }
        item(key = "not-in-library") {
            val series = summary.notInLibrary
            TextPreferenceWidget(
                title = stringResource(HR.strings.storage_remove_not_in_library),
                subtitle = if (series.isEmpty()) {
                    stringResource(HR.strings.storage_nothing_to_delete)
                } else {
                    pluralStringResource(
                        HR.plurals.storage_series_size,
                        series.size,
                        series.size,
                        formatSize(series.sumOf { it.size }),
                    )
                },
                onPreferenceClick = if (series.isEmpty()) null else ({ onDialog(Dialog.RemoveNotInLibrary(series)) }),
            )
        }
        item(key = "read") {
            TextPreferenceWidget(
                title = stringResource(HR.strings.storage_delete_read),
                subtitle = stringResource(HR.strings.storage_delete_read_summary),
                onPreferenceClick = onDeleteRead,
            )
        }

        item(key = "largest-header") { GroupHeader(stringResource(HR.strings.storage_largest)) }
        items(summary.largest, key = { "largest/${it.sourceFolder}/${it.folder.name}" }) { series ->
            SeriesRow(series, showSource = true, onDelete = { onDialog(Dialog.DeleteSeries(series)) })
        }

        item(key = "sources-header") { GroupHeader(stringResource(HR.strings.storage_by_source)) }
        for (source in summary.sources) {
            item(key = "source/${source.name}") {
                var expanded by rememberSaveable(source.name) { mutableStateOf(false) }
                Column {
                    TextPreferenceWidget(
                        title = source.name,
                        subtitle = pluralStringResource(
                            HR.plurals.storage_series_size,
                            source.series.size,
                            source.series.size,
                            formatSize(source.size),
                        ),
                        widget = {
                            Icon(
                                imageVector = MaterialSymbols.Rounded.KeyboardArrowDown,
                                contentDescription = null,
                                modifier = Modifier.rotate(if (expanded) 180f else 0f),
                            )
                        },
                        onPreferenceClick = { expanded = !expanded },
                    )
                    if (expanded) {
                        source.series.forEach { series ->
                            SeriesRow(
                                series,
                                showSource = false,
                                onDelete = { onDialog(Dialog.DeleteSeries(series)) },
                            )
                        }
                    }
                }
            }
        }

        if (summary.unrecognised.isNotEmpty()) {
            item(key = "unrecognised-header") { GroupHeader(stringResource(HR.strings.storage_unrecognised)) }
            item(key = "unrecognised-note") { Note(stringResource(HR.strings.storage_unrecognised_summary)) }
            items(summary.unrecognised, key = { "unrecognised/${it.sourceFolder}/${it.folder.name}" }) { series ->
                SeriesRow(series, showSource = true, onDelete = {})
            }
        }
    }

    private sealed interface Dialog {
        data class DeleteSeries(val series: StoredSeries) : Dialog
        data class RemoveNotInLibrary(val series: List<StoredSeries>) : Dialog
        data object DeleteRead : Dialog
    }
}

/** A series folder: name, then size with its status or source. Unrecognised folders get no delete action. */
@Composable
private fun SeriesRow(series: StoredSeries, showSource: Boolean, onDelete: () -> Unit) {
    val detail = if (showSource) series.sourceFolder else statusLabel(series.status)
    TextPreferenceWidget(
        modifier = Modifier.padding(start = if (showSource) 0.dp else 16.dp),
        title = series.folder.name,
        subtitle = stringResource(HR.strings.storage_size_line, formatSize(series.size), detail),
        widget = if (series.status == SeriesStatus.UNRECOGNISED) {
            null
        } else {
            {
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = MaterialSymbols.Rounded.Delete,
                        contentDescription = stringResource(HR.strings.storage_delete_series, series.folder.name),
                    )
                }
            }
        },
    )
}

@Composable
private fun statusLabel(status: SeriesStatus): String = stringResource(
    when (status) {
        SeriesStatus.IN_LIBRARY -> HR.strings.storage_status_in_library
        SeriesStatus.NOT_IN_LIBRARY -> HR.strings.storage_status_not_in_library
        SeriesStatus.UNRECOGNISED -> HR.strings.storage_status_unrecognised
    },
)

@Composable
private fun Progress(state: StorageManagerViewModel.State, modifier: Modifier) {
    Column(modifier = modifier.padding(horizontal = PrefsHorizontalPadding, vertical = 16.dp)) {
        val text = when (state.phase) {
            StorageManagerViewModel.Phase.MATCHING -> stringResource(HR.strings.storage_matching)
            StorageManagerViewModel.Phase.DELETING -> stringResource(HR.strings.storage_deleting)
            else -> stringResource(HR.strings.storage_scanning, state.scanned, state.toScan)
        }
        Text(text = text, style = MaterialTheme.typography.bodyMedium)
        if (state.phase == StorageManagerViewModel.Phase.SCANNING && state.toScan > 0) {
            LinearProgressIndicator(
                progress = { state.scanned.toFloat() / state.toScan },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        }
    }
}

@Composable
private fun DeleteReadDialog(
    totals: StorageManagerViewModel.ReadTotals?,
    onDismiss: () -> Unit,
    onConfirm: (includeReadCopies: Boolean) -> Unit,
) {
    var includeCopies by rememberSaveable { mutableStateOf(false) }
    val selected = totals?.let { if (includeCopies) it.withCopies else it.plain }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(HR.strings.storage_delete_read)) },
        text = {
            Column {
                when {
                    selected == null -> CircularProgressIndicator()
                    selected.count == 0 -> Text(text = stringResource(HR.strings.storage_nothing_to_delete))
                    else -> Text(
                        text = pluralStringResource(
                            HR.plurals.storage_delete_chapters_confirm,
                            selected.count,
                            selected.count,
                            formatSize(selected.size),
                        ),
                    )
                }
                LabeledCheckbox(
                    label = stringResource(HR.strings.storage_delete_read_copies),
                    checked = includeCopies,
                    onCheckedChange = { includeCopies = it },
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = selected != null && selected.count > 0,
                onClick = {
                    onDismiss()
                    onConfirm(includeCopies)
                },
            ) {
                Text(text = stringResource(HR.strings.storage_delete_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = stringResource(MR.strings.action_cancel)) }
        },
    )
}

@Composable
private fun ConfirmDialog(title: String, message: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title) },
        text = { Text(text = message) },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    onConfirm()
                },
            ) {
                Text(text = stringResource(HR.strings.storage_delete_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = stringResource(MR.strings.action_cancel)) }
        },
    )
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
private fun formatSize(bytes: Long): String {
    val context = LocalContext.current
    return remember(bytes) { Formatter.formatFileSize(context, bytes) }
}
