package haruka.updates

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.presentation.manga.components.ChapterDownloadIndicator
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.presentation.util.relativeTimeSpanString
import eu.kanade.tachiyomi.ui.updates.UpdatesItem
import haruka.i18n.HR
import haruka.title.rememberDisplayTitle
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.roundedfilled.Bookmark
import mihon.icons.materialsymbols.roundedfilled.Circle
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.DISABLED_ALPHA
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.selectedBackground

private val AvatarSize = 48.dp
private val AvatarGap = 12.dp

// Messages from the other side: square corner where the tail would be, under the avatar.
private val BubbleShape = RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomEnd = 16.dp, bottomStart = 16.dp)
private val BadgeShape = RoundedCornerShape(6.dp)

// Slate of a chat partner's bubble; white text on it is ~6.9:1, so it works with any app theme.
private val UnreadBubble = Color(0xFF4B5C6E)
private val OnUnreadBubble = Color.White

/**
 * One manga's chapters for a day as a chat conversation: the row stands for the whole group, like a collapsed
 * group of the grouped list, and tapping it shows each chapter as a message.
 */
@Composable
internal fun UpdatesConversation(
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
    Column(
        modifier = if (expanded) {
            // Tints the open conversation so its messages read as belonging to it.
            modifier.background(MaterialTheme.colorScheme.surfaceContainerLow)
        } else {
            modifier
        },
    ) {
        ConversationRow(
            group = group,
            expanded = expanded,
            onToggleExpanded = onToggleExpanded,
            selectionMode = selectionMode,
            onUpdateSelected = onUpdateSelected,
            onClickCover = onClickCover,
            onDownloadChapter = onDownloadChapter,
        )
        if (expanded) {
            Column(modifier = Modifier.padding(bottom = MaterialTheme.padding.small)) {
                // Oldest first, as in a chat, so the newest chapter sits last like the preview says.
                group.items.asReversed().forEach { item ->
                    ChapterBubble(
                        item = item,
                        selectionMode = selectionMode,
                        onUpdateSelected = onUpdateSelected,
                        onClickUpdate = onClickUpdate,
                        onDownloadChapter = onDownloadChapter,
                    )
                }
            }
        }
    }
}

@Composable
private fun ConversationRow(
    group: GroupedRow.Group<UpdatesItem>,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    selectionMode: Boolean,
    onUpdateSelected: (UpdatesItem, Boolean, Boolean) -> Unit,
    onClickCover: (UpdatesItem) -> Unit,
    onDownloadChapter: (List<UpdatesItem>, ChapterDownloadAction) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val leader = group.leader
    val selected = group.items.all { it.selected }
    val unreadCount = group.items.count { !it.update.read }
    val textAlpha = if (unreadCount == 0) DISABLED_ALPHA else 1f
    val toggleLabel = when {
        selectionMode -> null
        expanded -> stringResource(HR.strings.updates_group_collapse)
        else -> stringResource(HR.strings.updates_conversation_expand)
    }

    Row(
        modifier = Modifier
            .selectedBackground(selected)
            .combinedClickable(
                onClickLabel = toggleLabel,
                onClick = {
                    if (selectionMode) {
                        selectAll(group.items, !selected, fromLongPress = false, onUpdateSelected)
                    } else {
                        onToggleExpanded()
                    }
                },
                onLongClick = {
                    selectAll(group.items, !selected, fromLongPress = true, onUpdateSelected)
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                },
            )
            .heightIn(min = 72.dp)
            .padding(horizontal = MaterialTheme.padding.medium, vertical = MaterialTheme.padding.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MangaCover.Square(
            data = leader.update.coverData,
            shape = CircleShape,
            modifier = Modifier.size(AvatarSize),
            onClick = { onClickCover(leader) }.takeIf { !selectionMode },
        )
        Column(
            modifier = Modifier
                .padding(horizontal = AvatarGap)
                .weight(1f),
        ) {
            Text(
                text = rememberDisplayTitle(leader.update.mangaTitle),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = textAlpha),
            )
            Text(
                text = leader.update.chapterName,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = textAlpha),
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = relativeTimeSpanString(leader.update.dateFetch.toEpochMilliseconds()),
                maxLines = 1,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (unreadCount > 0) {
                UnreadBadge(count = unreadCount, modifier = Modifier.padding(top = 6.dp))
            }
        }
        // The conversation stands for its whole group, so starting a download fetches every chapter in it.
        ChapterDownloadIndicator(
            enabled = !selectionMode,
            modifier = Modifier.padding(start = 4.dp),
            downloadStateProvider = leader.downloadStateProvider,
            downloadProgressProvider = leader.downloadProgressProvider,
            onClick = { action -> downloadTargets(leader, group.items, action, onDownloadChapter) },
        )
    }
}

@Composable
private fun UnreadBadge(count: Int, modifier: Modifier = Modifier) {
    val description = pluralStringResource(HR.plurals.updates_conversation_unread, count, count)
    Box(
        modifier = modifier
            .clearAndSetSemantics { contentDescription = description }
            .heightIn(min = 20.dp)
            .widthIn(min = 20.dp)
            .clip(BadgeShape)
            .background(MaterialTheme.colorScheme.error)
            .padding(horizontal = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onError,
        )
    }
}

@Composable
private fun ChapterBubble(
    item: UpdatesItem,
    selectionMode: Boolean,
    onUpdateSelected: (UpdatesItem, Boolean, Boolean) -> Unit,
    onClickUpdate: (UpdatesItem) -> Unit,
    onDownloadChapter: (List<UpdatesItem>, ChapterDownloadAction) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val update = item.update
    val targets = listOf(item)
    // Read messages step back to a neutral container that keeps its theme-guaranteed text contrast.
    val container = if (update.read) MaterialTheme.colorScheme.surfaceContainerHighest else UnreadBubble
    val content = if (update.read) MaterialTheme.colorScheme.onSurfaceVariant else OnUnreadBubble
    val readProgress = update.lastPageRead
        .takeIf { !update.read && it > 0L }
        ?.let { stringResource(MR.strings.chapter_progress, it + 1) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectedBackground(item.selected)
            .combinedClickable(
                onClick = {
                    if (selectionMode) {
                        selectAll(targets, !item.selected, fromLongPress = false, onUpdateSelected)
                    } else {
                        onClickUpdate(item)
                    }
                },
                onLongClick = {
                    selectAll(targets, !item.selected, fromLongPress = true, onUpdateSelected)
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                },
            )
            .heightIn(min = 48.dp)
            .padding(
                start = MaterialTheme.padding.medium + AvatarSize + AvatarGap,
                end = MaterialTheme.padding.medium,
                top = 3.dp,
                bottom = 3.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            Column(
                modifier = Modifier
                    .clip(BubbleShape)
                    .background(container)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!update.read) {
                        Icon(
                            imageVector = MaterialSymbols.RoundedFilled.Circle,
                            contentDescription = stringResource(MR.strings.unread),
                            modifier = Modifier
                                .padding(end = 6.dp)
                                .size(8.dp),
                            tint = content,
                        )
                    }
                    if (update.bookmark) {
                        Icon(
                            imageVector = MaterialSymbols.RoundedFilled.Bookmark,
                            contentDescription = stringResource(MR.strings.action_filter_bookmarked),
                            modifier = Modifier.size(16.dp),
                            tint = content,
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                    }
                    Text(
                        text = update.chapterName,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                        color = content,
                    )
                }
                if (readProgress != null) {
                    Text(
                        text = readProgress,
                        maxLines = 1,
                        style = MaterialTheme.typography.labelSmall,
                        color = content,
                    )
                }
            }
        }
        ChapterDownloadIndicator(
            enabled = !selectionMode,
            modifier = Modifier.padding(start = 4.dp),
            downloadStateProvider = item.downloadStateProvider,
            downloadProgressProvider = item.downloadProgressProvider,
            onClick = { action -> downloadTargets(item, targets, action, onDownloadChapter) },
        )
    }
}
