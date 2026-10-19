package haruka.library.shelves

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import eu.kanade.presentation.util.formatChapterNumber
import mihon.app.di.appGraph
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.history.interactor.GetHistory
import kotlin.time.Instant

/** Where the reader stopped in a series: its last-read chapter, and the page when that chapter isn't finished. */
internal data class ReadingPoint(val chapterNumber: String, val page: Int?)

/** The interactors the carousel needs, contributed to the app graph without growing `HarukaGraph`. */
@ContributesTo(AppScope::class)
interface ShelvesGraph {
    val getHistory: GetHistory
    val getChapter: GetChapter
}

/**
 * Reads the last-read chapter of [mangaId] from its history. Chapters keep the last page read but not the page
 * count, so there is no "of N". Null when the series has no history or the chapter is gone.
 */
internal suspend fun Context.readingPoint(mangaId: Long): ReadingPoint? {
    val graph = appGraph as ShelvesGraph
    val last = graph.getHistory.await(mangaId).maxByOrNull { it.readAt ?: Instant.DISTANT_PAST } ?: return null
    val chapter = graph.getChapter.await(last.chapterId) ?: return null
    val number = if (chapter.chapterNumber >= 0) formatChapterNumber(chapter.chapterNumber) else chapter.name
    // lastPageRead is a 0-based index, and 0 also means "not opened"
    val page = if (!chapter.read && chapter.lastPageRead > 0) chapter.lastPageRead + 1 else null
    return ReadingPoint(number, page)
}
