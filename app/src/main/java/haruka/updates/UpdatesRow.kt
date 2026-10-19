package haruka.updates

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.relativeDateText
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.presentation.manga.components.ChapterDownloadIndicator
import eu.kanade.presentation.manga.components.DotSeparatorText
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.presentation.util.rememberResourceBitmapPainter
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.download.model.Download
import haruka.cover.PanoramaAwareCover
import haruka.cover.rememberPanoramaCoversEnabled
import haruka.i18n.HR
import haruka.motion.listEffectsSpec
import haruka.motion.listSpatialSpec
import haruka.title.rememberDisplayTitle
import kotlinx.datetime.LocalDate
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.KeyboardArrowDown
import mihon.icons.materialsymbols.roundedfilled.Bookmark
import mihon.icons.materialsymbols.roundedfilled.Circle
import tachiyomi.domain.updates.model.UpdatesWithRelations
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.DISABLED_ALPHA
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.selectedBackground

// Komikku's row cover: a 48 x 72dp book. Wide covers keep the book frame; with panorama covers on they are fitted
// inside it instead of cropped, so every row and every revealed chapter lines up at the same indent.
private val BookCoverWidth = 48.dp
private val CoverHeight = 72.dp

// Same as upstream MangaCover's placeholder, which is private to it.
private val CoverPlaceholder = Color(0x1F888888)

/**
 * One chapter of the grouped Updates list, with the behaviour of upstream's `UpdatesUiItem` in a roomier layout.
 *
 * A series row ([showSeries]) leads with the cover and title; a revealed row keeps only the chapter line, indented
 * by the cover's width. [expandButton] sits before the download button.
 */
@Composable
internal fun UpdatesRow(
    update: UpdatesWithRelations,
    selected: Boolean,
    readProgress: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onClickCover: (() -> Unit)?,
    onDownloadChapter: ((ChapterDownloadAction) -> Unit)?,
    downloadStateProvider: () -> Download.State,
    downloadProgressProvider: () -> Int,
    showSeries: Boolean,
    modifier: Modifier = Modifier,
    expandButton: @Composable () -> Unit = {},
) {
    val haptic = LocalHapticFeedback.current
    val textAlpha = if (update.read) DISABLED_ALPHA else 1f

    Row(
        modifier = modifier
            .selectedBackground(selected)
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    onLongClick()
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                },
            )
            .heightIn(min = 48.dp)
            .padding(
                horizontal = MaterialTheme.padding.medium,
                vertical = if (showSeries) MaterialTheme.padding.small else MaterialTheme.padding.extraSmall,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showSeries) {
            UpdatesCover(data = update.coverData, onClick = onClickCover)
        } else {
            Spacer(modifier = Modifier.width(BookCoverWidth))
        }

        Column(
            modifier = Modifier
                .padding(horizontal = MaterialTheme.padding.medium)
                .weight(1f),
        ) {
            if (showSeries) {
                Text(
                    text = rememberDisplayTitle(update.mangaTitle),
                    maxLines = 1,
                    style = MaterialTheme.typography.bodyLarge,
                    // Read rows dim but keep the weight, so the title still outranks the chapter line.
                    fontWeight = FontWeight.SemiBold,
                    color = LocalContentColor.current.copy(alpha = textAlpha),
                    overflow = TextOverflow.Ellipsis,
                )
            }
            ChapterLine(update = update, readProgress = readProgress, textAlpha = textAlpha)
        }

        expandButton()

        ChapterDownloadIndicator(
            enabled = onDownloadChapter != null,
            modifier = Modifier.padding(start = 4.dp),
            downloadStateProvider = downloadStateProvider,
            downloadProgressProvider = downloadProgressProvider,
            onClick = { onDownloadChapter?.invoke(it) },
        )
    }
}

@Composable
private fun UpdatesCover(data: Any?, onClick: (() -> Unit)?) {
    val shape = MaterialTheme.shapes.small
    if (rememberPanoramaCoversEnabled()) {
        PanoramaAwareCover(
            data = data,
            placeholder = ColorPainter(CoverPlaceholder),
            error = rememberResourceBitmapPainter(id = R.drawable.cover_error),
            contentDescription = "",
            onClick = onClick,
            modifier = Modifier
                .size(width = BookCoverWidth, height = CoverHeight)
                .clip(shape),
        )
    } else {
        MangaCover.Book(
            data = data,
            modifier = Modifier.width(BookCoverWidth),
            shape = shape,
            onClick = onClick,
        )
    }
}

@Composable
private fun ChapterLine(update: UpdatesWithRelations, readProgress: String?, textAlpha: Float) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        var textHeight by remember { mutableIntStateOf(0) }
        if (!update.read) {
            Icon(
                imageVector = MaterialSymbols.RoundedFilled.Circle,
                contentDescription = stringResource(MR.strings.unread),
                modifier = Modifier
                    .height(8.dp)
                    .padding(end = 4.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        if (update.bookmark) {
            Icon(
                imageVector = MaterialSymbols.RoundedFilled.Bookmark,
                contentDescription = stringResource(MR.strings.action_filter_bookmarked),
                modifier = Modifier
                    .sizeIn(maxHeight = with(LocalDensity.current) { textHeight.toDp() - 2.dp }),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(2.dp))
        }
        val parts = remember(update.chapterName, update.scanlator) {
            chapterLineParts(update.chapterName, update.scanlator)
        }
        val extraColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = textAlpha)
        Text(
            text = if (parts.extra == null) {
                AnnotatedString(parts.chapter)
            } else {
                buildAnnotatedString {
                    append(parts.chapter)
                    append(' ')
                    withStyle(SpanStyle(color = extraColor)) { append(parts.extra) }
                }
            },
            maxLines = 1,
            style = MaterialTheme.typography.bodyMedium,
            // Medium, not Normal: Zen Maru Gothic's Regular is lighter than the system font and reads too thin here.
            fontWeight = FontWeight.Medium,
            color = LocalContentColor.current.copy(alpha = textAlpha),
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { textHeight = it.size.height },
            modifier = Modifier.weight(weight = 1f, fill = false),
        )
        if (readProgress != null) {
            DotSeparatorText()
            Text(
                text = readProgress,
                maxLines = 1,
                style = MaterialTheme.typography.bodyMedium,
                color = LocalContentColor.current.copy(alpha = DISABLED_ALPHA),
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * "+N" pill with a chevron that flips while the group is open, in the style of the chapter list's group chip. The
 * pill is 32dp tall; its touch target is reserved at the 48dp minimum so it can't bleed into the download button.
 */
@Composable
internal fun GroupExpandButton(hiddenCount: Int, expanded: Boolean, onClick: () -> Unit) {
    val description = if (expanded) {
        stringResource(HR.strings.updates_group_hide)
    } else {
        pluralStringResource(HR.plurals.updates_group_show_more, hiddenCount, hiddenCount)
    }
    val colors = MaterialTheme.colorScheme
    val tintSpec = listEffectsSpec<Color>(spring())
    val container by animateColorAsState(
        if (expanded) colors.secondaryContainer else colors.surfaceContainerHighest,
        animationSpec = tintSpec,
        label = "updatesGroupToggleContainer",
    )
    val content by animateColorAsState(
        if (expanded) colors.onSecondaryContainer else colors.onSurfaceVariant,
        animationSpec = tintSpec,
        label = "updatesGroupToggleContent",
    )
    val chevronRotation by animateFloatAsState(
        if (expanded) 180f else 0f,
        animationSpec = listSpatialSpec(spring()),
        label = "updatesGroupToggleChevron",
    )

    Row(
        modifier = Modifier
            .padding(start = 4.dp)
            .minimumInteractiveComponentSize()
            .height(32.dp)
            .clip(CircleShape)
            .background(container)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .padding(start = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "+$hiddenCount",
            style = MaterialTheme.typography.labelMedium,
            color = content,
            maxLines = 1,
            modifier = Modifier.clearAndSetSemantics {},
        )
        Icon(
            imageVector = MaterialSymbols.Rounded.KeyboardArrowDown,
            contentDescription = null,
            tint = content,
            modifier = Modifier
                .padding(start = 2.dp)
                .size(18.dp)
                .rotate(chevronRotation),
        )
    }
}

/** A day's heading with how many chapters arrived that day, as Komikku shows it. */
@Composable
internal fun UpdatesDateHeader(date: LocalDate, chapterCount: Int, modifier: Modifier = Modifier) {
    val countDescription = pluralStringResource(MR.plurals.manga_num_chapters, chapterCount, chapterCount.toString())
    Row(
        modifier = modifier
            .semantics(mergeDescendants = true) { heading() }
            .padding(horizontal = MaterialTheme.padding.medium, vertical = MaterialTheme.padding.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = relativeDateText(date),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.titleSmall,
        )
        Box(
            modifier = Modifier
                .padding(start = MaterialTheme.padding.small)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer)
                .padding(horizontal = 6.dp)
                .clearAndSetSemantics { contentDescription = countDescription },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = chapterCount.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}
