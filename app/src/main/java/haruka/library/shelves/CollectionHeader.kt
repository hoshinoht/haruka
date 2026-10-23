package haruka.library.shelves

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.category.visualName
import haruka.i18n.HR
import haruka.motion.FilterChip
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Close
import tachiyomi.domain.category.model.Category
import tachiyomi.presentation.core.i18n.stringResource

/** Widens a full-span grid item over [bleed] of horizontal grid padding on each side, to reach the screen edges. */
internal fun Modifier.horizontalBleed(bleed: Dp): Modifier = layout { measurable, constraints ->
    val extra = (bleed * 2).roundToPx()
    val width = constraints.maxWidth + extra
    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-extra / 2, 0) }
}

/**
 * The pinned chip row over the Collection: the "See all" filter and, when the library has categories, a chip per
 * category in place of upstream's tabs. Shown only when there is a chip; the toolbar already carries the count.
 *
 * @param bleed horizontal grid padding the header extends over, so it spans the screen edge to edge.
 */
@Composable
internal fun CollectionHeader(
    categories: List<Category>?,
    currentPage: Int,
    getItemCountForCategory: (Category) -> Int?,
    onChangeCurrentPage: (Int) -> Unit,
    pinned: Boolean,
    bleed: Dp,
    filterTitle: String?,
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
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (filterTitle != null) ShelfFilterChip(filterTitle, onClearFilter)
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
        HorizontalDivider(
            color = if (pinned) {
                MaterialTheme.colorScheme.surfaceContainerHighest
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            },
        )
    }
}

/** The temporary "See all" filter, named [title]: tapping it, or its ×, shows every series again. */
@Composable
private fun ShelfFilterChip(title: String, onClear: () -> Unit) {
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
