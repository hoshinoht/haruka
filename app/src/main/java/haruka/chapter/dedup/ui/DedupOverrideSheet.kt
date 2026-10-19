package haruka.chapter.dedup.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import dev.icerock.moko.resources.StringResource
import eu.kanade.presentation.components.AdaptiveSheet
import haruka.chapter.dedup.MangaDedupOverride
import haruka.di.harukaGraph
import haruka.i18n.HR
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.HeadingItem
import tachiyomi.presentation.core.components.SettingsItemsPaddings
import tachiyomi.presentation.core.components.material.TextButton
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.theme.header
import tachiyomi.presentation.core.util.collectAsState
import tachiyomi.presentation.core.util.secondaryItemAlpha

/** Button for the entry's scanlator filter dialog that opens [DedupOverrideSheet]. */
@Composable
fun DedupOverrideButton(mangaId: Long, scanlators: Set<String>) {
    var showSheet by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { showSheet = true }) {
        Text(text = stringResource(HR.strings.dedup_title))
    }
    if (showSheet) {
        DedupOverrideSheet(mangaId = mangaId, scanlators = scanlators, onDismissRequest = { showSheet = false })
    }
}

/**
 * Per-entry dedup choices: grouping and split merging that can differ from the global switches, and an optional
 * ranking of this entry's own scanlators that replaces the global ranking for it.
 */
@Composable
fun DedupOverrideSheet(mangaId: Long, scanlators: Set<String>, onDismissRequest: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { context.harukaGraph.dedupSettings }
    val catalog = remember { context.harukaGraph.officialCatalogLoader.catalog }
    val globalGroup by settings.groupChapters.collectAsState()
    val globalMerge by settings.mergeSplitChapters.collectAsState()
    val globalRanking by settings.rankedScanlators.collectAsState()

    val saved = remember(mangaId) { settings.override(mangaId) ?: MangaDedupOverride() }
    var groupChapters by remember(saved) { mutableStateOf(saved.groupChapters) }
    var mergeSplitChapters by remember(saved) { mutableStateOf(saved.mergeSplitChapters) }
    // Null follows the global ranking; the first reorder turns the shown order into this entry's own ranking.
    var ranking by remember(saved) { mutableStateOf(saved.rankedScanlators) }

    val names = remember(scanlators) { DedupRanking.groups(scanlators) }
    val shown = remember(names, ranking, globalRanking) { DedupRanking.ordered(ranking ?: globalRanking, names) }

    AdaptiveSheet(onDismissRequest = onDismissRequest) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(top = MaterialTheme.padding.medium),
            ) {
                Text(
                    text = stringResource(HR.strings.dedup_title),
                    style = MaterialTheme.typography.header,
                    modifier = Modifier.padding(horizontal = SettingsItemsPaddings.Horizontal),
                )
                TriStateButtons(
                    label = HR.strings.dedup_group,
                    value = groupChapters,
                    globalValue = globalGroup,
                    onChange = { groupChapters = it },
                )
                TriStateButtons(
                    label = HR.strings.dedup_merge_split,
                    value = mergeSplitChapters,
                    globalValue = globalMerge,
                    onChange = { mergeSplitChapters = it },
                )

                HeadingItem(HR.strings.dedup_ranking)
                Row(
                    modifier = Modifier.padding(horizontal = SettingsItemsPaddings.Horizontal),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(
                            if (ranking == null) {
                                HR.strings.dedup_manga_ranking_global
                            } else {
                                HR.strings.dedup_manga_ranking_custom
                            },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .weight(1f)
                            .secondaryItemAlpha(),
                    )
                    if (ranking != null) {
                        TextButton(onClick = { ranking = null }) {
                            Text(text = stringResource(HR.strings.dedup_use_global))
                        }
                    }
                }
                if (names.isEmpty()) {
                    Text(
                        text = stringResource(MR.strings.no_scanlators_found),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(
                            horizontal = SettingsItemsPaddings.Horizontal,
                            vertical = SettingsItemsPaddings.Vertical,
                        ),
                    )
                }
                shown.forEachIndexed { index, name ->
                    ReorderRow(
                        title = name,
                        supporting = officialLabel(catalog.statusOf(name)),
                        index = index,
                        count = shown.size,
                        onMoveUp = { ranking = DedupRanking.moveUp(shown, index) },
                        onMoveDown = { ranking = DedupRanking.moveDown(shown, index) },
                        startInset = SettingsItemsPaddings.Horizontal,
                    )
                }
            }
            HorizontalDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MaterialTheme.padding.medium, vertical = MaterialTheme.padding.small),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismissRequest) {
                    Text(text = stringResource(MR.strings.action_cancel))
                }
                TextButton(
                    onClick = {
                        settings.setOverride(
                            mangaId,
                            MangaDedupOverride(
                                groupChapters = groupChapters,
                                mergeSplitChapters = mergeSplitChapters,
                                rankedScanlators = ranking,
                            ),
                        )
                        onDismissRequest()
                    },
                ) {
                    Text(text = stringResource(MR.strings.action_save))
                }
            }
        }
    }
}

/** Default (follows [globalValue]) / On / Off as a connected single-select button group, where null means default. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TriStateButtons(
    label: StringResource,
    value: Boolean?,
    globalValue: Boolean,
    onChange: (Boolean?) -> Unit,
) {
    val on = stringResource(MR.strings.on)
    val off = stringResource(MR.strings.off)
    val options = listOf(
        null to stringResource(HR.strings.dedup_default_value, if (globalValue) on else off),
        true to on,
        false to off,
    )
    Column {
        HeadingItem(label)
        Row(
            modifier = Modifier
                .padding(
                    start = SettingsItemsPaddings.Horizontal,
                    end = SettingsItemsPaddings.Horizontal,
                    bottom = SettingsItemsPaddings.Vertical,
                )
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
        ) {
            options.forEachIndexed { index, (option, text) ->
                ToggleButton(
                    checked = value == option,
                    onCheckedChange = { onChange(option) },
                    shapes = when (index) {
                        0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                        options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                        else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                    },
                    modifier = Modifier.semantics { role = Role.RadioButton },
                ) {
                    Text(text = text, maxLines = 1)
                }
            }
        }
    }
}
