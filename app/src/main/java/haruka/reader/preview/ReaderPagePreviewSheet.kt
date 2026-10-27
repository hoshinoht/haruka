package haruka.reader.preview

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import haruka.i18n.HR
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.roundedfilled.ViewModule
import tachiyomi.presentation.core.i18n.stringResource

/** Bottom-bar button that opens [ReaderPagePreviewSheet]. */
@Composable
fun ReaderPagePreviewAction(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            imageVector = MaterialSymbols.RoundedFilled.ViewModule,
            contentDescription = stringResource(HR.strings.reader_page_preview),
        )
    }
}

/**
 * The current chapter's pages as a grid of thumbnails; tapping one jumps to it and closes the sheet.
 *
 * Thumbnails come from [PageThumbnails] for pages already loaded ([Page.State.Ready] with a stream). Pages still
 * loading show their number with a progress indicator; pages not requested yet, failed, or undecodable show their
 * number alone. The current page is outlined and scrolled into view when the sheet opens.
 */
@Composable
fun ReaderPagePreviewSheet(
    viewModel: ReaderViewModel,
    onDismissRequest: () -> Unit,
    onSelectPage: (Int) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val chapter = state.currentChapter
    val chapterId = chapter?.chapter?.id
    val pages = chapter?.pages.orEmpty()
    // currentPage is 1-based, -1 before the viewer reports one
    val currentIndex = state.currentPage - 1
    remember(chapterId) { chapterId?.let(PageThumbnails::useChapter) }

    val configuration = LocalConfiguration.current
    val columns = if (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 5 else 3
    val gridState = rememberLazyGridState(
        // The current page's row second from the top, so the row before it shows too
        initialFirstVisibleItemIndex = ((currentIndex / columns - 1) * columns).coerceAtLeast(0),
    )
    val maxHeight = configuration.screenHeightDp.dp * 0.7f

    AdaptiveSheet(onDismissRequest = onDismissRequest) {
        Column {
            Text(
                text = stringResource(HR.strings.reader_page_preview),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 24.dp, top = 24.dp, end = 24.dp, bottom = 8.dp),
            )
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                state = gridState,
                modifier = Modifier.heightIn(max = maxHeight),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(items = pages, key = { index, _ -> index }) { index, page ->
                    PageCell(
                        chapterId = chapterId,
                        index = index,
                        page = page,
                        isCurrent = index == currentIndex,
                        onClick = {
                            onSelectPage(index)
                            onDismissRequest()
                        },
                    )
                }
            }
        }
    }
}

/** A cell's thumbnail, and whether decoding it is over (so a page with none stops showing progress). */
private data class Thumbnail(val bitmap: Bitmap?, val settled: Boolean)

@Composable
private fun PageCell(
    chapterId: Long?,
    index: Int,
    page: ReaderPage,
    isCurrent: Boolean,
    onClick: () -> Unit,
) {
    val status by page.statusFlow.collectAsState()
    val thumbnail by produceState(
        initialValue = chapterId?.let { PageThumbnails.cached(it, index) }
            .let { Thumbnail(bitmap = it, settled = it != null) },
        chapterId,
        index,
        status,
    ) {
        if (value.bitmap != null || chapterId == null || status != Page.State.Ready) return@produceState
        value = Thumbnail(bitmap = PageThumbnails.load(chapterId, index, page), settled = true)
    }
    val loading = status == Page.State.LoadPage ||
        status == Page.State.DownloadImage ||
        (status == Page.State.Ready && !thumbnail.settled)

    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.small
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(2f / 3f)
            .clip(shape)
            .background(colors.surfaceContainerHighest)
            .then(
                if (isCurrent) Modifier.border(BorderStroke(3.dp, colors.primary), shape) else Modifier,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = thumbnail.bitmap
        if (bitmap != null) {
            val image = remember(bitmap) { bitmap.asImageBitmap() }
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (loading) {
            CircularProgressIndicator(
                strokeWidth = 2.dp,
                modifier = Modifier.size(20.dp),
            )
        }
        Text(
            text = (index + 1).toString(),
            style = MaterialTheme.typography.labelMedium,
            color = if (isCurrent) colors.onPrimary else colors.onSurface,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 4.dp)
                .clip(MaterialTheme.shapes.extraSmall)
                .background(if (isCurrent) colors.primary else colors.surface.copy(alpha = 0.8f))
                .padding(horizontal = 6.dp, vertical = 1.dp),
        )
    }
}
