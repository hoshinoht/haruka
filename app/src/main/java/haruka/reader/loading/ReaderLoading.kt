package haruka.reader.loading

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import haruka.di.harukaGraph
import haruka.i18n.HR
import haruka.preference.HarukaPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Page loading settings for upstream's `HttpPageLoader`, which has no context to reach the graph. The reader installs
 * them when it loads a chapter; until then every value is upstream's.
 */
object ReaderLoading {

    @Volatile
    private var preferences: HarukaPreferences? = null

    fun install(context: Context) {
        if (preferences == null) preferences = context.harukaGraph.harukaPreferences
    }

    /** Rolling chunk sizes for the first pages of a chapter, before the reader asks for any of them. */
    fun preloadChunks(): Pair<Int, Int> =
        preloadChunkSizes(preferences?.parallelPageDownloads?.get() ?: DEFAULT_PARALLEL_DOWNLOADS)

    /** Largest chunk of pages of the chapter being read that download together. */
    fun pagesAtOnce(): Int =
        (preferences?.pagesDownloadedAtOnce?.get() ?: DEFAULT_PAGES_AT_ONCE).coerceIn(3, 8)
}

/**
 * Whether the next chapter may start preloading behind [chapter], see [shouldPrefetchNextChapter], updated as its pages
 * load and on every [pageChanges] emission. The page being read is the chapter's own [ReaderChapter.requestedPage],
 * which the reader moves on every page of this chapter it shows: the reader-wide current page can still belong to the
 * previous chapter right after a chapter switch.
 */
fun canPrefetchNextChapter(chapter: ReaderChapter, pageChanges: Flow<*>): Flow<Boolean> {
    val pages = chapter.pages.orEmpty()
    if (pages.isEmpty()) return flowOf(true)
    val done = combine(pages.map { it.statusFlow }) { states ->
        states.map { it == Page.State.Ready || it is Page.State.Error }
    }
    return combine(done, pageChanges) { isDone, _ -> shouldPrefetchNextChapter(isDone, chapter.requestedPage) }
}

/** Settings > Reader group with the page loading settings. */
@Composable
fun readerLoadingPreferences(): Preference.PreferenceGroup {
    val context = LocalContext.current
    val preferences = remember { context.harukaGraph.harukaPreferences }
    return Preference.PreferenceGroup(
        title = stringResource(HR.strings.pref_category_haruka),
        preferenceItems = listOf(
            Preference.PreferenceItem.ListPreference(
                preference = preferences.parallelPageDownloads,
                entries = listOf(1, 2, 3).associateWith { it.toString() },
                title = stringResource(HR.strings.pref_parallel_page_downloads),
                subtitleProvider = { value, entries ->
                    stringResource(HR.strings.pref_parallel_page_downloads_summary, entries[value].orEmpty())
                },
            ),
            Preference.PreferenceItem.ListPreference(
                preference = preferences.pagesDownloadedAtOnce,
                entries = listOf(3, 5, 8).associateWith { it.toString() },
                title = stringResource(HR.strings.pref_pages_downloaded_at_once),
                subtitleProvider = { value, entries ->
                    stringResource(HR.strings.pref_pages_downloaded_at_once_summary, entries[value].orEmpty())
                },
            ),
        ),
    )
}
