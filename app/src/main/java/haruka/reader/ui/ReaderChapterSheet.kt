package haruka.reader.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.presentation.components.relativeDateText
import eu.kanade.presentation.manga.components.MangaChapterListItem
import eu.kanade.presentation.util.formatChapterNumber
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirroredrounded.FormatListBulleted
import tachiyomi.domain.library.service.LibraryPreferences.ChapterSwipeAction
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/** Bottom-bar button that opens [ReaderChapterSheet]. */
@Composable
fun ReaderChapterListAction(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            imageVector = MaterialSymbols.AutoMirroredRounded.FormatListBulleted,
            contentDescription = stringResource(MR.strings.chapters),
        )
    }
}

/**
 * The reader's chapters in a sheet, drawn with the manga screen's chapter rows; tapping one opens it.
 *
 * The reader keeps its chapters in reading order (ascending, see `ReaderViewModel.getChapterSort(manga, false)`),
 * already filtered and deduplicated like the reader navigates them. The sheet shows them in the manga
 * screen's direction instead: reversed (newest first) when the series sorts descending, which is the default.
 */
@Composable
fun ReaderChapterSheet(
    viewModel: ReaderViewModel,
    onDismissRequest: () -> Unit,
    onSelectChapter: (ReaderChapter) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val manga = state.manga
    val current = state.currentChapter
    val chapters = remember(manga) {
        val readingOrder = viewModel.harukaChapters()
        if (manga?.sortDescending() != false) readingOrder.asReversed() else readingOrder
    }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = (chapters.indexOf(current) - 2).coerceAtLeast(0),
    )
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.7f

    AdaptiveSheet(onDismissRequest = onDismissRequest) {
        Column {
            Text(
                text = stringResource(MR.strings.chapters),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 24.dp, top = 24.dp, end = 24.dp, bottom = 8.dp),
            )
            LazyColumn(
                state = listState,
                modifier = Modifier.heightIn(max = maxHeight),
            ) {
                items(items = chapters, key = { it.chapter.id }) { item ->
                    ChapterRow(
                        item = item,
                        manga = manga,
                        isCurrent = item == current,
                        onClick = { onSelectChapter(item) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ChapterRow(
    item: ReaderChapter,
    manga: Manga?,
    isCurrent: Boolean,
    onClick: () -> Unit,
) {
    val chapter = item.chapter
    MangaChapterListItem(
        title = if (manga?.displayMode == Manga.CHAPTER_DISPLAY_NUMBER) {
            stringResource(MR.strings.display_mode_chapter, formatChapterNumber(chapter.chapterNumber))
        } else {
            chapter.name
        },
        date = relativeDateText(chapter.dateUpload),
        readProgress = chapter.lastPageRead
            .takeIf { !chapter.read && it > 0L }
            ?.let { stringResource(MR.strings.chapter_progress, it + 1) },
        scanlator = chapter.scanlator.takeIf { !it.isNullOrBlank() },
        read = chapter.read,
        bookmark = chapter.bookmark,
        selected = isCurrent,
        downloadIndicatorEnabled = false,
        downloadStateProvider = { Download.State.NOT_DOWNLOADED },
        downloadProgressProvider = { 0 },
        chapterSwipeStartAction = ChapterSwipeAction.Disabled,
        chapterSwipeEndAction = ChapterSwipeAction.Disabled,
        onLongClick = {},
        onClick = onClick,
        onDownloadClick = null,
        onChapterSwipe = {},
    )
}
