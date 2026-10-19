package haruka.chapter.dedup.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.icerock.moko.resources.StringResource
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.widget.PrefsHorizontalPadding
import eu.kanade.presentation.more.settings.widget.SwitchPreferenceWidget
import eu.kanade.presentation.util.Screen
import haruka.chapter.dedup.OfficialCatalog
import haruka.chapter.dedup.ScanlatorNames
import haruka.i18n.HR
import mihon.app.di.appGraph
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.TextButton
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import tachiyomi.presentation.core.util.secondaryItemAlpha
import tachiyomi.core.common.preference.Preference as StoredPreference

/** Settings > Library entry that opens [DedupSettingsScreen]. */
@Composable
fun dedupSettingsPreference(): Preference.PreferenceItem.TextPreference {
    val navigator = LocalNavigator.currentOrThrow
    return Preference.PreferenceItem.TextPreference(
        title = stringResource(HR.strings.dedup_title),
        subtitle = stringResource(HR.strings.dedup_summary),
        onClick = { navigator.push(DedupSettingsScreen()) },
    )
}

/** Global dedup switches, the order of the rules that pick a chapter's copy, and the scanlator ranking. */
class DedupSettingsScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val settings = remember { context.appGraph.dedupSettings }
        val catalog = remember { context.appGraph.officialCatalogLoader.catalog }
        val viewModel = metroViewModel<DedupSettingsViewModel>()
        val suggestions by viewModel.suggestions.collectAsStateWithLifecycle()
        val rules by settings.rules.collectAsState()
        val ranking by settings.rankedScanlators.collectAsState()
        var showAddDialog by rememberSaveable { mutableStateOf(false) }

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(HR.strings.dedup_title),
                    navigateUp = navigator::pop,
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { contentPadding ->
            LazyColumn(contentPadding = contentPadding) {
                item(key = "group") {
                    SwitchItem(settings.groupChapters, HR.strings.dedup_group, HR.strings.dedup_group_summary)
                }
                item(key = "merge") {
                    SwitchItem(
                        settings.mergeSplitChapters,
                        HR.strings.dedup_merge_split,
                        HR.strings.dedup_merge_split_summary,
                    )
                }
                item(key = "mark-read") {
                    SwitchItem(
                        settings.markDuplicatesRead,
                        HR.strings.dedup_mark_duplicates_read,
                        HR.strings.dedup_mark_duplicates_read_summary,
                    )
                }
                item(key = "skip-downloaded") {
                    SwitchItem(
                        settings.skipDownloadedDuplicates,
                        HR.strings.dedup_skip_downloaded_duplicates,
                        HR.strings.dedup_skip_downloaded_duplicates_summary,
                    )
                }

                item(key = "rules-header") {
                    SectionHeader(title = stringResource(HR.strings.dedup_rules)) {
                        TextButton(onClick = settings.rules::delete, enabled = !DedupRanking.isDefault(rules)) {
                            Text(text = stringResource(MR.strings.action_reset))
                        }
                    }
                }
                item(key = "rules-summary") { SectionNote(stringResource(HR.strings.dedup_rules_summary)) }
                itemsIndexed(rules, key = { _, rule -> "rule-${rule.name}" }) { index, rule ->
                    ReorderRow(
                        title = ruleLabel(rule),
                        supporting = null,
                        index = index,
                        count = rules.size,
                        onMoveUp = { settings.rules.set(DedupRanking.moveUp(rules, index)) },
                        onMoveDown = { settings.rules.set(DedupRanking.moveDown(rules, index)) },
                        startInset = PrefsHorizontalPadding,
                        modifier = haruka.motion.listItemMotion(this),
                    )
                }

                item(key = "ranking-header") {
                    SectionHeader(title = stringResource(HR.strings.dedup_ranking)) {
                        TextButton(onClick = settings.rankedScanlators::delete, enabled = ranking.isNotEmpty()) {
                            Text(text = stringResource(MR.strings.action_reset))
                        }
                        TextButton(
                            onClick = {
                                viewModel.loadSuggestions()
                                showAddDialog = true
                            },
                        ) {
                            Text(text = stringResource(MR.strings.action_add))
                        }
                    }
                }
                if (ranking.isEmpty()) {
                    item(key = "ranking-empty") { SectionNote(stringResource(HR.strings.dedup_ranking_empty)) }
                }
                // Normalized names are unique within the ranking, so they make stable keys.
                itemsIndexed(ranking, key = { _, name -> "rank-${ScanlatorNames.normalize(name)}" }) { index, name ->
                    ReorderRow(
                        title = name,
                        supporting = officialLabel(catalog.statusOf(name)),
                        index = index,
                        count = ranking.size,
                        onMoveUp = { settings.rankedScanlators.set(DedupRanking.moveUp(ranking, index)) },
                        onMoveDown = { settings.rankedScanlators.set(DedupRanking.moveDown(ranking, index)) },
                        onRemove = { settings.rankedScanlators.set(ranking.filterIndexed { i, _ -> i != index }) },
                        startInset = PrefsHorizontalPadding,
                        modifier = haruka.motion.listItemMotion(this),
                    )
                }
            }
        }

        if (showAddDialog) {
            AddScanlatorDialog(
                ranking = ranking,
                catalog = catalog,
                suggestions = suggestions,
                onDismissRequest = { showAddDialog = false },
                onAdd = { name ->
                    settings.rankedScanlators.set(DedupRanking.add(ranking, name))
                    showAddDialog = false
                },
            )
        }
    }
}

@Composable
private fun SwitchItem(
    preference: StoredPreference<Boolean>,
    title: StringResource,
    subtitle: StringResource,
) {
    val checked by preference.collectAsState()
    SwitchPreferenceWidget(
        title = stringResource(title),
        subtitle = stringResource(subtitle),
        checked = checked,
        onCheckedChanged = preference::set,
    )
}

/** [PreferenceGroupHeader][eu.kanade.presentation.more.settings.widget.PreferenceGroupHeader] with trailing actions. */
@Composable
private fun SectionHeader(title: String, actions: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // The 48dp action buttons centre the title 14dp from the top, matching Mihon's group header inset.
            .padding(start = PrefsHorizontalPadding, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            color = MaterialTheme.colorScheme.secondary,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
        )
        actions()
    }
}

@Composable
private fun SectionNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier
            .padding(horizontal = PrefsHorizontalPadding, vertical = 4.dp)
            .secondaryItemAlpha(),
    )
}

/** Free-text entry with the library's not yet ranked scanlators as tappable suggestions. */
@Composable
private fun AddScanlatorDialog(
    ranking: List<String>,
    catalog: OfficialCatalog,
    suggestions: List<String>?,
    onDismissRequest: () -> Unit,
    onAdd: (String) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    val candidates = remember(suggestions, ranking) { suggestions?.let { DedupRanking.unranked(it, ranking) } }
    val query = text.trim()
    val matches = remember(candidates, query) { candidates?.filter { it.contains(query, ignoreCase = true) } }
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(text = stringResource(HR.strings.dedup_add_scanlator)) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(text = stringResource(HR.strings.dedup_scanlator_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (matches == null) {
                    Text(
                        text = stringResource(HR.strings.dedup_suggestions_loading),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 280.dp)) {
                        items(matches, key = { it }) { name ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onAdd(name) }
                                    .padding(vertical = 12.dp),
                            ) {
                                Text(text = name, style = MaterialTheme.typography.bodyMedium)
                                officialLabel(catalog.statusOf(name))?.let {
                                    Text(
                                        text = it,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(start = 8.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAdd(query) },
                enabled = ScanlatorNames.normalize(query).isNotEmpty(),
            ) {
                Text(text = stringResource(MR.strings.action_add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
    )
}
