package haruka.library.shelves

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.kanade.core.preference.PreferenceMutableState
import eu.kanade.presentation.library.components.CommonMangaItemDefaults
import eu.kanade.presentation.library.components.DownloadsBadge
import eu.kanade.presentation.library.components.GlobalSearchItem
import eu.kanade.presentation.library.components.LanguageBadge
import eu.kanade.presentation.library.components.MangaComfortableGridItem
import eu.kanade.presentation.library.components.MangaCompactGridItem
import eu.kanade.presentation.library.components.MangaListItem
import eu.kanade.presentation.library.components.UnreadBadge
import eu.kanade.tachiyomi.ui.library.LibraryItem
import haruka.library.Section
import haruka.library.SortSections
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tachiyomi.core.common.util.lang.compareToWithCollator
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.library.model.sort
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.FastScrollLazyVerticalGrid
import tachiyomi.presentation.core.components.material.PullRefresh
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.util.plus
import java.time.Instant
import java.time.ZoneId
import kotlin.time.Duration.Companion.seconds

private val TitleCollator = Comparator<String> { a, b -> a.compareToWithCollator(b) }

/**
 * The library as one scroll: a pinned Collection header over the current category's grid, split by dividers that
 * follow its sort. Takes [eu.kanade.presentation.library.components.LibraryContent]'s parameters and keeps its
 * behaviour: display mode, columns, badges, search, selection, continue reading and pull to refresh.
 */
@Composable
fun ShelvesLibrary(
    categories: List<Category>,
    searchQuery: String?,
    selection: Set<Long>,
    contentPadding: PaddingValues,
    currentPage: Int,
    hasActiveFilters: Boolean,
    showPageTabs: Boolean,
    onChangeCurrentPage: (Int) -> Unit,
    onClickManga: (Long) -> Unit,
    onContinueReadingClicked: ((LibraryManga) -> Unit)?,
    onToggleSelection: (Category, LibraryManga) -> Unit,
    onToggleRangeSelection: (Category, LibraryManga) -> Unit,
    onRefresh: suspend () -> Boolean,
    onGlobalSearchClicked: () -> Unit,
    getItemCountForCategory: (Category) -> Int?,
    getDisplayMode: (Int) -> PreferenceMutableState<LibraryDisplayMode>,
    getColumnsForOrientation: (Boolean) -> PreferenceMutableState<Int>,
    getItemsForCategory: (Category) -> List<LibraryItem>,
) {
    val category = categories.getOrNull(currentPage) ?: return
    val items = getItemsForCategory(category)
    val sort = category.sort
    // The same condition as upstream's tabs, so a single user-made category still gets its chip
    val chipCategories = categories.takeIf {
        showPageTabs && it.isNotEmpty() && (it.size > 1 || !it.first().isSystemCategory)
    }

    val scope = rememberCoroutineScope()
    var isRefreshing by remember(currentPage) { mutableStateOf(false) }

    val displayMode by getDisplayMode(currentPage)
    val isList = displayMode == LibraryDisplayMode.List
    val columns by if (!isList) {
        val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
        remember(isLandscape) { getColumnsForOrientation(isLandscape) }
    } else {
        remember { mutableIntStateOf(0) }
    }
    // The list scrolls without horizontal padding, like upstream's LibraryList
    val bleed = if (isList) 0.dp else 8.dp

    PullRefresh(
        refreshing = isRefreshing,
        enabled = selection.isEmpty(),
        onRefresh = {
            scope.launch {
                if (!onRefresh()) return@launch
                // Fake refresh status but hide it after a second as it's a long running task
                isRefreshing = true
                delay(1.seconds)
                isRefreshing = false
            }
        },
    ) {
        val header: @Composable (Boolean, Dp) -> Unit = { isPinned, headerBleed ->
            CollectionHeader(
                count = getItemCountForCategory(category) ?: items.size,
                sort = sort,
                categories = chipCategories,
                currentPage = currentPage,
                getItemCountForCategory = getItemCountForCategory,
                onChangeCurrentPage = onChangeCurrentPage,
                pinned = isPinned,
                bleed = headerBleed,
            )
        }

        if (items.isEmpty()) {
            EmptyCollection(
                searchQuery = searchQuery,
                hasActiveFilters = hasActiveFilters,
                contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding()),
                onGlobalSearchClicked = onGlobalSearchClicked,
                header = { header(false, 0.dp) },
            )
            return@PullRefresh
        }

        // Recomputed only when the list or its sort changes, never while scrolling
        val sections = remember(items, sort.type) {
            SortSections(Instant.now(), ZoneId.systemDefault(), TitleCollator)
                .sections(sort.type.toSectionSort(), items.map { it.toShelfInput() })
        }
        val monthFormatter = rememberMonthFormatter()

        val gridState = rememberSaveable(currentPage, saver = LazyGridState.Saver) { LazyGridState() }
        val headerIndex = if (searchQuery.isNullOrEmpty()) 0 else 1
        val pinned by remember(headerIndex) {
            derivedStateOf {
                gridState.firstVisibleItemIndex > headerIndex ||
                    (gridState.firstVisibleItemIndex == headerIndex && gridState.firstVisibleItemScrollOffset > 0)
            }
        }

        val onClick: (LibraryManga) -> Unit = {
            if (selection.isNotEmpty()) onToggleSelection(category, it) else onClickManga(it.manga.id)
        }
        val onLongClick: (LibraryManga) -> Unit = { onToggleRangeSelection(category, it) }

        FastScrollLazyVerticalGrid(
            columns = when {
                isList -> GridCells.Fixed(1)
                columns == 0 -> GridCells.Adaptive(128.dp)
                else -> GridCells.Fixed(columns)
            },
            modifier = Modifier.fillMaxSize(),
            state = gridState,
            contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding()) +
                if (isList) PaddingValues(vertical = 8.dp) else PaddingValues(8.dp),
            verticalArrangement = if (isList) {
                Arrangement.Top
            } else {
                Arrangement.spacedBy(CommonMangaItemDefaults.GridVerticalSpacer)
            },
            horizontalArrangement = if (isList) {
                Arrangement.Start
            } else {
                Arrangement.spacedBy(CommonMangaItemDefaults.GridHorizontalSpacer)
            },
        ) {
            if (!searchQuery.isNullOrEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }, contentType = "library_global_search_item") {
                    GlobalSearchItem(
                        searchQuery = searchQuery,
                        onClick = onGlobalSearchClicked,
                        modifier = if (isList) Modifier.fillMaxWidth() else Modifier,
                    )
                }
            }

            stickyHeader(key = "collection", contentType = "collection_header") { header(pinned, bleed) }

            val item: @Composable (LibraryItem) -> Unit = { libraryItem ->
                LibraryEntry(
                    libraryItem = libraryItem,
                    displayMode = displayMode,
                    selection = selection,
                    onClick = onClick,
                    onLongClick = onLongClick,
                    onClickContinueReading = onContinueReadingClicked,
                )
            }
            if (sections.isEmpty()) {
                entries(items, 0, items.size, item)
            } else {
                for (section in sections) {
                    divider(section) { SectionPill(section.key.label(monthFormatter)) }
                    entries(items, section.start, section.count, item)
                }
            }
        }
    }
}

private fun LazyGridScope.divider(section: Section, content: @Composable () -> Unit) {
    item(
        key = "div-${section.key.id}",
        span = { GridItemSpan(maxLineSpan) },
        contentType = "divider",
    ) { content() }
}

private fun LazyGridScope.entries(
    items: List<LibraryItem>,
    start: Int,
    count: Int,
    content: @Composable (LibraryItem) -> Unit,
) {
    items(
        count = count,
        key = { items[start + it].id },
        contentType = { "manga" },
    ) { content(items[start + it]) }
}

// The per-item blocks of upstream's LibraryCompactGrid, LibraryComfortableGrid and LibraryList.
@Composable
private fun LibraryEntry(
    libraryItem: LibraryItem,
    displayMode: LibraryDisplayMode,
    selection: Set<Long>,
    onClick: (LibraryManga) -> Unit,
    onLongClick: (LibraryManga) -> Unit,
    onClickContinueReading: ((LibraryManga) -> Unit)?,
) {
    val manga = libraryItem.libraryManga.manga
    val coverData = MangaCover(
        mangaId = manga.id,
        sourceId = manga.source,
        isMangaFavorite = manga.favorite,
        url = manga.thumbnailUrl,
        lastModified = manga.coverLastModified,
    )
    val continueReading: (() -> Unit)? = if (onClickContinueReading != null && libraryItem.unreadCount > 0) {
        { onClickContinueReading(libraryItem.libraryManga) }
    } else {
        null
    }
    when (displayMode) {
        LibraryDisplayMode.CompactGrid, LibraryDisplayMode.CoverOnlyGrid -> MangaCompactGridItem(
            isSelected = manga.id in selection,
            title = manga.title.takeIf { displayMode is LibraryDisplayMode.CompactGrid },
            coverData = coverData,
            coverBadgeStart = {
                DownloadsBadge(count = libraryItem.badges.downloadCount)
                UnreadBadge(count = libraryItem.badges.unreadCount)
            },
            coverBadgeEnd = {
                LanguageBadge(
                    isLocal = libraryItem.badges.isLocal,
                    sourceLanguage = libraryItem.badges.sourceLanguage,
                )
            },
            onLongClick = { onLongClick(libraryItem.libraryManga) },
            onClick = { onClick(libraryItem.libraryManga) },
            onClickContinueReading = continueReading,
        )
        LibraryDisplayMode.ComfortableGrid -> MangaComfortableGridItem(
            isSelected = manga.id in selection,
            title = manga.title,
            coverData = coverData,
            coverBadgeStart = {
                DownloadsBadge(count = libraryItem.badges.downloadCount)
                UnreadBadge(count = libraryItem.badges.unreadCount)
            },
            coverBadgeEnd = {
                LanguageBadge(
                    isLocal = libraryItem.badges.isLocal,
                    sourceLanguage = libraryItem.badges.sourceLanguage,
                )
            },
            onLongClick = { onLongClick(libraryItem.libraryManga) },
            onClick = { onClick(libraryItem.libraryManga) },
            onClickContinueReading = continueReading,
        )
        LibraryDisplayMode.List -> MangaListItem(
            isSelected = manga.id in selection,
            title = manga.title,
            coverData = coverData,
            badge = {
                DownloadsBadge(count = libraryItem.badges.downloadCount)
                UnreadBadge(count = libraryItem.badges.unreadCount)
                LanguageBadge(
                    isLocal = libraryItem.badges.isLocal,
                    sourceLanguage = libraryItem.badges.sourceLanguage,
                )
            },
            onLongClick = { onLongClick(libraryItem.libraryManga) },
            onClick = { onClick(libraryItem.libraryManga) },
            onClickContinueReading = continueReading,
        )
    }
}

// Upstream's LibraryPagerEmptyScreen (private there), under the Collection header.
@Composable
private fun EmptyCollection(
    searchQuery: String?,
    hasActiveFilters: Boolean,
    contentPadding: PaddingValues,
    onGlobalSearchClicked: () -> Unit,
    header: @Composable () -> Unit,
) {
    val msg = when {
        !searchQuery.isNullOrEmpty() -> MR.strings.no_results_found
        hasActiveFilters -> MR.strings.error_no_match
        else -> MR.strings.information_no_manga_category
    }

    Column(
        modifier = Modifier
            .padding(contentPadding)
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        header()
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(8.dp),
        ) {
            if (!searchQuery.isNullOrEmpty()) {
                GlobalSearchItem(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.CenterHorizontally),
                    searchQuery = searchQuery,
                    onClick = onGlobalSearchClicked,
                )
            }

            EmptyScreen(
                stringRes = msg,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
