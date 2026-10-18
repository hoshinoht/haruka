package haruka.updates

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.relativeDateText
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.presentation.updates.UpdatesUiItem
import eu.kanade.tachiyomi.ui.updates.UpdatesItem
import eu.kanade.tachiyomi.util.lang.toLocalDate
import haruka.i18n.HR
import mihon.app.di.appGraph
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.KeyboardArrowDown
import mihon.icons.materialsymbols.rounded.KeyboardArrowUp
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.ListGroupHeader
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

/**
 * Expanded groups survive scrolling and list refreshes, but not leaving the screen.
 *
 * [conversations] draws each group as a chat conversation; it implies [enabled].
 */
class UpdatesGroupingState(
    val enabled: Boolean,
    val conversations: Boolean,
    val expanded: SnapshotStateMap<GroupKey, Boolean>,
)

@Composable
fun rememberUpdatesGroupingState(): UpdatesGroupingState {
    val context = LocalContext.current
    val groupPreference = remember { context.appGraph.harukaPreferences.groupUpdates }
    val conversationsPreference = remember { context.appGraph.harukaPreferences.momotalkUpdates }
    val grouped by groupPreference.collectAsState()
    val conversations by conversationsPreference.collectAsState()
    val expanded = remember { mutableStateMapOf<GroupKey, Boolean>() }
    return UpdatesGroupingState(grouped || conversations, conversations, expanded)
}

/**
 * Updates list with each manga's chapters for a day collapsed under its newest chapter, or drawn as
 * conversations when [UpdatesGroupingState.conversations] is on.
 *
 * While a group is collapsed, actions on its leader row apply to every chapter in the group.
 */
fun LazyListScope.groupedUpdatesUiItems(
    items: List<UpdatesItem>,
    grouping: UpdatesGroupingState,
    selectionMode: Boolean,
    onUpdateSelected: (UpdatesItem, Boolean, Boolean) -> Unit,
    onClickCover: (UpdatesItem) -> Unit,
    onClickUpdate: (UpdatesItem) -> Unit,
    onDownloadChapter: (List<UpdatesItem>, ChapterDownloadAction) -> Unit,
) {
    val rows = groupUpdates(
        items = items,
        date = { it.update.dateFetch.toLocalDate() },
        mangaId = { it.update.mangaId },
    )

    items(
        items = rows,
        contentType = {
            when (it) {
                is GroupedRow.Header -> "header"
                is GroupedRow.Group -> if (grouping.conversations) "conversation" else "group"
            }
        },
        key = {
            when (it) {
                is GroupedRow.Header -> "updatesHeader-${it.date}"
                is GroupedRow.Group -> "updatesGroup-${it.key.date}-${it.key.mangaId}"
            }
        },
    ) { row ->
        when (row) {
            is GroupedRow.Header -> ListGroupHeader(
                modifier = Modifier.animateItem(),
                text = relativeDateText(row.date),
            )
            is GroupedRow.Group -> if (grouping.conversations) {
                UpdatesConversation(
                    group = row,
                    expanded = grouping.expanded[row.key] == true,
                    onToggleExpanded = { grouping.expanded[row.key] = grouping.expanded[row.key] != true },
                    selectionMode = selectionMode,
                    onUpdateSelected = onUpdateSelected,
                    onClickCover = onClickCover,
                    onClickUpdate = onClickUpdate,
                    onDownloadChapter = onDownloadChapter,
                    modifier = Modifier.animateItem(),
                )
            } else {
                UpdatesGroup(
                    group = row,
                    expanded = grouping.expanded[row.key] == true,
                    onToggleExpanded = { grouping.expanded[row.key] = grouping.expanded[row.key] != true },
                    selectionMode = selectionMode,
                    onUpdateSelected = onUpdateSelected,
                    onClickCover = onClickCover,
                    onClickUpdate = onClickUpdate,
                    onDownloadChapter = onDownloadChapter,
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }
}

@Composable
private fun UpdatesGroup(
    group: GroupedRow.Group<UpdatesItem>,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    selectionMode: Boolean,
    onUpdateSelected: (UpdatesItem, Boolean, Boolean) -> Unit,
    onClickCover: (UpdatesItem) -> Unit,
    onClickUpdate: (UpdatesItem) -> Unit,
    onDownloadChapter: (List<UpdatesItem>, ChapterDownloadAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val collapsed = group.items.size > 1 && !expanded
    // Collapsed: the leader row stands for the whole group.
    val leaderTargets = if (collapsed) group.items else listOf(group.leader)

    Column(modifier = modifier) {
        UpdateRow(
            item = group.leader,
            targets = leaderTargets,
            selected = leaderTargets.all { it.selected },
            selectionMode = selectionMode,
            onUpdateSelected = onUpdateSelected,
            onClickCover = onClickCover,
            onClickUpdate = onClickUpdate,
            onDownloadChapter = onDownloadChapter,
        )
        if (group.items.size > 1) {
            ExpandToggle(hiddenCount = group.hidden.size, expanded = expanded, onClick = onToggleExpanded)
        }
        if (expanded) {
            group.hidden.forEach { item ->
                UpdateRow(
                    item = item,
                    targets = listOf(item),
                    selected = item.selected,
                    selectionMode = selectionMode,
                    onUpdateSelected = onUpdateSelected,
                    onClickCover = onClickCover,
                    onClickUpdate = onClickUpdate,
                    onDownloadChapter = onDownloadChapter,
                )
            }
        }
    }
}

@Composable
private fun UpdateRow(
    item: UpdatesItem,
    targets: List<UpdatesItem>,
    selected: Boolean,
    selectionMode: Boolean,
    onUpdateSelected: (UpdatesItem, Boolean, Boolean) -> Unit,
    onClickCover: (UpdatesItem) -> Unit,
    onClickUpdate: (UpdatesItem) -> Unit,
    onDownloadChapter: (List<UpdatesItem>, ChapterDownloadAction) -> Unit,
) {
    fun select(fromLongPress: Boolean) = selectAll(targets, !selected, fromLongPress, onUpdateSelected)
    UpdatesUiItem(
        update = item.update,
        selected = selected,
        readProgress = item.update.lastPageRead
            .takeIf { !item.update.read && it > 0L }
            ?.let { stringResource(MR.strings.chapter_progress, it + 1) },
        onClick = { if (selectionMode) select(fromLongPress = false) else onClickUpdate(item) },
        onLongClick = { select(fromLongPress = true) },
        onClickCover = { onClickCover(item) }.takeIf { !selectionMode },
        onDownloadChapter = { action: ChapterDownloadAction ->
            downloadTargets(item, targets, action, onDownloadChapter)
        }.takeIf { !selectionMode },
        downloadStateProvider = item.downloadStateProvider,
        downloadProgressProvider = item.downloadProgressProvider,
    )
}

/** Selects or deselects every one of [targets] as one action. */
internal fun selectAll(
    targets: List<UpdatesItem>,
    select: Boolean,
    fromLongPress: Boolean,
    onUpdateSelected: (UpdatesItem, Boolean, Boolean) -> Unit,
) {
    // The first target goes through upstream's long-press range logic; the rest follow it.
    onUpdateSelected(targets.first(), select, fromLongPress)
    targets.drop(1).forEach { onUpdateSelected(it, select, false) }
}

/** Download/cancel the whole of [targets]; delete and per-chapter actions stay on [item]. */
internal fun downloadTargets(
    item: UpdatesItem,
    targets: List<UpdatesItem>,
    action: ChapterDownloadAction,
    onDownloadChapter: (List<UpdatesItem>, ChapterDownloadAction) -> Unit,
) {
    onDownloadChapter(if (action == ChapterDownloadAction.START) targets else listOf(item), action)
}

@Composable
private fun ExpandToggle(hiddenCount: Int, expanded: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 72.dp, end = 16.dp, top = 2.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (expanded) {
                MaterialSymbols.Rounded.KeyboardArrowUp
            } else {
                MaterialSymbols.Rounded.KeyboardArrowDown
            },
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = if (expanded) {
                stringResource(HR.strings.updates_group_collapse)
            } else {
                pluralStringResource(HR.plurals.updates_group_more_chapters, hiddenCount, hiddenCount)
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}
