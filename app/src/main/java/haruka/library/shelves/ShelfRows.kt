package haruka.library.shelves

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.carousel.HorizontalMultiBrowseCarousel
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.tachiyomi.ui.library.LibraryItem
import haruka.i18n.HR
import haruka.title.rememberDisplayTitle
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.roundedfilled.PlayArrow
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.domain.manga.model.MangaCover as MangaCoverData

private val CarouselHeight = 232.dp
private val CarouselItemWidth = 248.dp
private val ShelfCoverWidth = 112.dp
private val ShelfCoverHeight = ShelfCoverWidth * 3 / 2
private val SeeAllWidth = 88.dp
private val RowPadding = PaddingValues(horizontal = 16.dp)

// MomoTalk's message bubble: the lower-left corner is tucked like a speech tail.
private val BubbleShape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp, bottomEnd = 12.dp, bottomStart = 4.dp)

// Same as upstream's grid items (CommonMangaItem's GRID_SELECTED_COVER_ALPHA, private there)
private const val SELECTED_COVER_ALPHA = 0.76f

private fun LibraryItem.coverData() = libraryManga.manga.let {
    MangaCoverData(
        mangaId = it.id,
        sourceId = it.source,
        isMangaFavorite = it.favorite,
        url = it.thumbnailUrl,
        lastModified = it.coverLastModified,
    )
}

/** Clicks shared by every shelf entry: a tap opens or toggles, a long-press joins the selection. */
internal class ShelfActions(
    val selection: Set<Long>,
    val onClick: (LibraryItem) -> Unit,
    val onLongClick: (LibraryItem) -> Unit,
)

@Composable
internal fun ShelfTitle(title: String, count: Int?, onSeeAll: (() -> Unit)?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(start = 16.dp, end = 4.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() },
        )
        if (count != null) {
            val description = pluralStringResource(HR.plurals.library_series_count, count, count)
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .clearAndSetSemantics { contentDescription = description },
            )
        }
        Spacer(Modifier.weight(1f))
        if (onSeeAll != null) {
            TextButton(onClick = onSeeAll) {
                Text(
                    text = stringResource(HR.strings.library_shelf_see_all),
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }
}

/**
 * Started series with unread chapters as an M3 Expressive multi-browse carousel. The large item carries a
 * MomoTalk bubble (title and where the reader stopped) and a resume button.
 */
@Composable
internal fun ContinueReadingCarousel(
    items: List<LibraryItem>,
    readingPoints: Map<Long, ReadingPoint?>,
    actions: ShelfActions,
    onResume: ((LibraryItem) -> Unit)?,
) {
    val state = rememberCarouselState { items.size }
    HorizontalMultiBrowseCarousel(
        state = state,
        preferredItemWidth = CarouselItemWidth,
        modifier = Modifier
            .fillMaxWidth()
            .height(CarouselHeight),
        itemSpacing = 8.dp,
        contentPadding = RowPadding,
    ) { index ->
        val item = items[index]
        val title = rememberDisplayTitle(item.libraryManga.manga.title)
        val isSelected = item.id in actions.selection
        val drawInfo = carouselItemDrawInfo
        // Only the large item shows its bubble and resume button: on smaller ones they'd sit under the mask.
        val isLarge by remember { derivedStateOf { drawInfo.size >= drawInfo.maxSize * 0.9f } }
        val haptic = LocalHapticFeedback.current
        Box(
            modifier = Modifier
                .fillMaxSize()
                .maskClip(MaterialTheme.shapes.large)
                .then(
                    if (isSelected) {
                        Modifier.maskBorder(
                            BorderStroke(3.dp, MaterialTheme.colorScheme.secondary),
                            MaterialTheme.shapes.large,
                        )
                    } else {
                        Modifier
                    },
                )
                .semantics { selected = isSelected }
                .combinedClickable(
                    onClickLabel = title,
                    onClick = { actions.onClick(item) },
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        actions.onLongClick(item)
                    },
                ),
        ) {
            AsyncImage(
                model = item.coverData(),
                placeholder = ColorPainter(MaterialTheme.colorScheme.surfaceContainerHighest),
                error = ColorPainter(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(if (isSelected) SELECTED_COVER_ALPHA else 1f),
            )
            if (isLarge) {
                if (onResume != null) {
                    val resume = stringResource(HR.strings.library_shelf_resume, title)
                    FilledIconButton(
                        onClick = { onResume(item) },
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .semantics { contentDescription = resume },
                    ) {
                        Icon(imageVector = MaterialSymbols.RoundedFilled.PlayArrow, contentDescription = null)
                    }
                }
                ReadingBubble(
                    title = title,
                    point = readingPoints[item.id],
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp),
                )
            }
        }
    }
}

@Composable
private fun ReadingBubble(title: String, point: ReadingPoint?, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(BubbleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (point != null) {
            Text(
                text = if (point.page != null) {
                    stringResource(HR.strings.library_shelf_chapter_page, point.chapterNumber, point.page)
                } else {
                    stringResource(HR.strings.library_shelf_chapter, point.chapterNumber)
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * A horizontal row of covers with titles below, ending in a dashed "See all" tile.
 *
 * @param sourceUnavailable marks every cover with a "Source unavailable" chip (the Needs a new source shelf).
 */
@Composable
internal fun ShelfRow(
    items: List<LibraryItem>,
    total: Int,
    actions: ShelfActions,
    onSeeAll: () -> Unit,
    sourceUnavailable: Boolean = false,
) {
    LazyRow(
        contentPadding = RowPadding,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items, key = { it.id }, contentType = { "shelf_cover" }) { item ->
            ShelfCover(item, actions, sourceUnavailable)
        }
        item(key = "see-all", contentType = "see_all") {
            SeeAllTile(total, onSeeAll)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShelfCover(item: LibraryItem, actions: ShelfActions, sourceUnavailable: Boolean) {
    val title = rememberDisplayTitle(item.libraryManga.manga.title)
    val isSelected = item.id in actions.selection
    val haptic = LocalHapticFeedback.current
    Column(
        modifier = Modifier
            .width(ShelfCoverWidth)
            .clip(MaterialTheme.shapes.small)
            .semantics(mergeDescendants = true) { selected = isSelected }
            .combinedClickable(
                onClick = { actions.onClick(item) },
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    actions.onLongClick(item)
                },
            ),
    ) {
        Box {
            MangaCover.Book(
                data = item.coverData(),
                shape = MaterialTheme.shapes.small,
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(if (isSelected) SELECTED_COVER_ALPHA else 1f)
                    .then(
                        if (isSelected) {
                            Modifier.border(3.dp, MaterialTheme.colorScheme.secondary, MaterialTheme.shapes.small)
                        } else {
                            Modifier
                        },
                    ),
            )
            val unread = item.unreadCount
            if (unread > 0) {
                UnreadDot(
                    count = unread,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp),
                )
            }
            if (sourceUnavailable) {
                SourceUnavailableChip(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(4.dp),
                )
            }
        }
        Text(
            text = title,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
        )
    }
}

/** The design's warning chip: error container on the cover's foot, so it reads on any art. */
@Composable
private fun SourceUnavailableChip(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(HR.strings.library_shelf_source_unavailable),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onErrorContainer,
        textAlign = TextAlign.Center,
        // Two lines rather than an ellipsis: longer translations still read in full on a narrow cover
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .clip(MaterialTheme.shapes.extraSmall)
            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.94f))
            .padding(horizontal = 4.dp, vertical = 3.dp),
    )
}

/** MomoTalk's coral unread dot, as in Updates conversations, with a surface ring so it separates from any cover. */
@Composable
private fun UnreadDot(count: Long, modifier: Modifier = Modifier) {
    val description = pluralStringResource(HR.plurals.library_shelf_unread, count.toInt(), count.toInt())
    Box(
        modifier = modifier
            .clearAndSetSemantics { contentDescription = description }
            .background(MaterialTheme.colorScheme.surface, CircleShape)
            .padding(1.5.dp)
            .heightIn(min = 20.dp)
            .widthIn(min = 20.dp)
            .background(MaterialTheme.colorScheme.error, CircleShape)
            .padding(horizontal = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onError,
        )
    }
}

@Composable
private fun SeeAllTile(total: Int, onClick: () -> Unit) {
    val outline = MaterialTheme.colorScheme.outlineVariant
    val label = stringResource(HR.strings.library_shelf_see_all)
    Box(
        modifier = Modifier
            .width(SeeAllWidth)
            .height(ShelfCoverHeight)
            .clip(MaterialTheme.shapes.small)
            .drawBehind { drawDashedOutline(outline) }
            .combinedClickable(role = Role.Button, onClick = onClick)
            .padding(4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "$label\n$total",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.tertiary,
            textAlign = TextAlign.Center,
        )
    }
}

private fun DrawScope.drawDashedOutline(color: Color) {
    val stroke = 1.5.dp.toPx()
    val radius = 12.dp.toPx()
    drawRoundRect(
        color = color,
        topLeft = Offset(stroke / 2, stroke / 2),
        size = Size(size.width - stroke, size.height - stroke),
        cornerRadius = CornerRadius(radius),
        style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))),
    )
}
