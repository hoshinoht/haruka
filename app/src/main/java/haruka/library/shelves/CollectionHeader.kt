package haruka.library.shelves

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.category.visualName
import haruka.i18n.HR
import haruka.library.ShelfKind
import haruka.motion.FilterChip
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.ArrowDownward
import mihon.icons.materialsymbols.rounded.ArrowUpward
import mihon.icons.materialsymbols.rounded.Close
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.model.LibrarySort
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource

/** Widens a full-span grid item over [bleed] of horizontal grid padding on each side, to reach the screen edges. */
internal fun Modifier.horizontalBleed(bleed: Dp): Modifier = layout { measurable, constraints ->
    val extra = (bleed * 2).roundToPx()
    val width = constraints.maxWidth + extra
    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-extra / 2, 0) }
}

/**
 * The pinned "All series" header: count, the active sort and, when the library has categories, a chip per
 * category in place of upstream's tabs.
 *
 * @param bleed horizontal grid padding the header extends over, so it spans the screen edge to edge.
 */
@Composable
internal fun CollectionHeader(
    count: Int,
    sort: LibrarySort,
    categories: List<Category>?,
    currentPage: Int,
    getItemCountForCategory: (Category) -> Int?,
    onChangeCurrentPage: (Int) -> Unit,
    pinned: Boolean,
    bleed: Dp,
    filter: ShelfKind?,
    onClearFilter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .horizontalBleed(bleed)
            .background(MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val countDescription = pluralStringResource(HR.plurals.library_series_count, count, count)
            Text(
                text = stringResource(HR.strings.library_all_series),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .clearAndSetSemantics { contentDescription = countDescription },
            )
            Spacer(Modifier.weight(1f))
            SortLabel(sort)
        }
        if (!categories.isNullOrEmpty() || filter != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (filter != null) ShelfFilterChip(filter, onClearFilter)
                categories.orEmpty().forEachIndexed { index, category ->
                    val itemCount = getItemCountForCategory(category)
                    FilterChip(
                        selected = index == currentPage,
                        onClick = { onChangeCurrentPage(index) },
                        label = {
                            Text(if (itemCount == null) category.visualName else "${category.visualName} · $itemCount")
                        },
                    )
                }
            }
        }
        HorizontalDivider(
            color = if (pinned) {
                MaterialTheme.colorScheme.surfaceContainerHighest
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            },
        )
    }
}

/** The temporary "See all" filter: tapping it, or its ×, shows every series again. */
@Composable
private fun ShelfFilterChip(filter: ShelfKind, onClear: () -> Unit) {
    val title = stringResource(filter.title)
    val description = stringResource(HR.strings.library_shelf_filter_clear, title)
    FilterChip(
        selected = true,
        onClick = onClear,
        label = { Text(title) },
        trailingIcon = {
            Icon(
                imageVector = MaterialSymbols.Rounded.Close,
                contentDescription = null,
                modifier = Modifier.size(FilterChipDefaults.IconSize),
            )
        },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
            selectedTrailingIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        modifier = Modifier.semantics { contentDescription = description },
    )
}

// Label only: LibraryContent has no callback that opens the sort sheet, and wiring one in would take a second
// upstream hook in LibraryTab.
@Composable
private fun SortLabel(sort: LibrarySort) {
    val label = stringResource(sortTitle(sort.type))
    val hasDirection = sort.type != LibrarySort.Type.Random
    val direction = stringResource(if (sort.isAscending) MR.strings.action_asc else MR.strings.action_desc)
    val description = if (hasDirection) stringResource(HR.strings.library_sort_description, label, direction) else label
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(start = if (hasDirection) 8.dp else 12.dp, end = 12.dp, top = 6.dp, bottom = 6.dp)
            .clearAndSetSemantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (hasDirection) {
            Icon(
                imageVector = if (sort.isAscending) {
                    MaterialSymbols.Rounded.ArrowUpward
                } else {
                    MaterialSymbols.Rounded.ArrowDownward
                },
                contentDescription = null,
                modifier = Modifier
                    .padding(end = 4.dp)
                    .size(16.dp),
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// The same titles as the sort sheet (LibrarySettingsDialog).
private fun sortTitle(type: LibrarySort.Type) = when (type) {
    LibrarySort.Type.Alphabetical -> MR.strings.action_sort_alpha
    LibrarySort.Type.TotalChapters -> MR.strings.action_sort_total
    LibrarySort.Type.LastRead -> MR.strings.action_sort_last_read
    LibrarySort.Type.LastUpdate -> MR.strings.action_sort_last_manga_update
    LibrarySort.Type.UnreadCount -> MR.strings.action_sort_unread_count
    LibrarySort.Type.LatestChapter -> MR.strings.action_sort_latest_chapter
    LibrarySort.Type.ChapterFetchDate -> MR.strings.action_sort_chapter_fetch_date
    LibrarySort.Type.DateAdded -> MR.strings.action_sort_date_added
    LibrarySort.Type.TrackerMean -> MR.strings.action_sort_tracker_score
    LibrarySort.Type.Random -> MR.strings.action_sort_random
}

/** A MomoTalk-style divider between groups, centred like a chat date separator. */
@Composable
internal fun SectionPill(label: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .padding(horizontal = 14.dp, vertical = 4.dp)
                .semantics { heading() },
        )
    }
}
