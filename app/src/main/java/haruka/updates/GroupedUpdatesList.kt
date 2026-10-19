package haruka.updates

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.tachiyomi.ui.updates.UpdatesItem
import eu.kanade.tachiyomi.util.lang.toLocalDate
import haruka.di.harukaGraph
import haruka.motion.ExpandableRows
import haruka.motion.listItemMotion
import tachiyomi.i18n.MR
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
    val groupPreference = remember { context.harukaGraph.harukaPreferences.groupUpdates }
    val conversationsPreference = remember { context.harukaGraph.harukaPreferences.momotalkUpdates }
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
fun groupedUpdatesUiItems(
    scope: LazyListScope,
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

    scope.items(
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
            is GroupedRow.Header -> UpdatesDateHeader(
                date = row.date,
                chapterCount = row.chapterCount,
                modifier = listItemMotion(this),
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
                    modifier = listItemMotion(this),
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
                    modifier = listItemMotion(this),
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
            showSeries = true,
            expandButton = {
                if (group.items.size > 1) {
                    GroupExpandButton(hiddenCount = group.hidden.size, expanded = expanded, onClick = onToggleExpanded)
                }
            },
        )
        ExpandableRows(expanded) {
            group.hidden.forEachIndexed { index, item ->
                UpdateRow(
                    item = item,
                    targets = listOf(item),
                    selected = item.selected,
                    selectionMode = selectionMode,
                    onUpdateSelected = onUpdateSelected,
                    onClickCover = onClickCover,
                    onClickUpdate = onClickUpdate,
                    onDownloadChapter = onDownloadChapter,
                    showSeries = false,
                    modifier = Modifier.revealedRow(index),
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
    showSeries: Boolean,
    modifier: Modifier = Modifier,
    expandButton: @Composable () -> Unit = {},
) {
    fun select(fromLongPress: Boolean) = selectAll(targets, !selected, fromLongPress, onUpdateSelected)
    UpdatesRow(
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
        showSeries = showSeries,
        modifier = modifier,
        expandButton = expandButton,
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
