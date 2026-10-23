package haruka.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.domain.manga.interactor.SetMangaViewerFlags
import eu.kanade.domain.manga.model.readingMode
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import haruka.di.harukaGraph
import haruka.i18n.HR
import haruka.preference.HarukaPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.preference.plusAssign
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Picks Long strip for a series left on the Default reading mode: from its tags before the first viewer is made, or
 * else from the shape of a few pages of the first chapter opened. Long strip is saved as the series' mode, like a
 * manual pick; normal pages save nothing, so the series keeps following the global default, and are only remembered
 * on this device so they aren't measured again.
 */
@Inject
@SingleIn(AppScope::class)
class AutoReadingMode(
    private val preferences: HarukaPreferences,
    private val sourceManager: SourceManager,
    private val setMangaViewerFlags: SetMangaViewerFlags,
) {

    private fun step(manga: Manga, sourceName: String?): AutoModeStep = autoModeStep(
        enabled = preferences.autoReadingMode.get(),
        storedModeIsDefault = manga.readingMode == ReadingMode.DEFAULT.flagValue.toLong(),
        decided = manga.id.toString() in preferences.autoReadingModeDecided.get(),
        genres = manga.genre,
        sourceName = sourceName,
    )

    private suspend fun sourceName(manga: Manga): String? = sourceManager.get(manga.source)?.name

    private fun markDecided(manga: Manga) {
        preferences.autoReadingModeDecided += manga.id.toString()
    }

    /** [manga] as the reader should open it: with Long strip saved as its mode when its tags call for it. */
    suspend fun onOpen(manga: Manga): Manga {
        if (step(manga, sourceName(manga)) != AutoModeStep.LongStripFromTags) return manga
        val longStrip = ReadingMode.WEBTOON.flagValue.toLong()
        setMangaViewerFlags.awaitSetReadingMode(manga.id, longStrip)
        markDecided(manga)
        return manga.copy(viewerFlags = manga.viewerFlags and ReadingMode.MASK.toLong().inv() or longStrip)
    }

    /**
     * Once the pages to sample from [chapter] are ready, reads their sizes from the image headers and, for a long
     * strip, hands Long strip to [setReadingMode], which saves it and switches the viewer. [currentManga] is checked
     * again before deciding, so a mode the user picked meanwhile wins.
     */
    fun checkPageShape(
        scope: CoroutineScope,
        chapter: ReaderChapter,
        currentManga: () -> Manga?,
        setReadingMode: (ReadingMode) -> Unit,
    ) {
        val pages = chapter.pages ?: return
        scope.launch(Dispatchers.IO) {
            val manga = currentManga() ?: return@launch
            val sourceName = sourceName(manga)
            if (step(manga, sourceName) != AutoModeStep.MeasurePages) return@launch
            val sizes = PageShape.sampleIndices(pages.size).mapNotNull { pages[it].awaitSize() }
            // Nothing readable (unsupported format, failed pages): try again next time
            val longStrip = PageShape.isLongStrip(sizes) ?: return@launch
            withContext(Dispatchers.Main) {
                // Main, like the user's picks, so none lands between this check and the switch
                val current = currentManga() ?: return@withContext
                if (step(current, sourceName) != AutoModeStep.MeasurePages) return@withContext
                markDecided(current)
                if (longStrip) setReadingMode(ReadingMode.WEBTOON)
            }
        }
    }

    /**
     * Waits for the loader to make this page ready, without asking for it, then reads its size from the header of
     * the stream the loader set: the chapter cache file for online chapters, the archive entry or file otherwise.
     */
    private suspend fun ReaderPage.awaitSize(): PageSize? {
        if (statusFlow.first { it == Page.State.Ready || it is Page.State.Error } != Page.State.Ready) return null
        val open = stream ?: return null
        return try {
            open().use(ImageHeader::readSize)
        } catch (e: Exception) {
            // The chapter can be recycled under us, closing its archive or evicting its cache file
            logcat(LogPriority.WARN, e) { "Couldn't read the size of page $index" }
            null
        }
    }
}

@Composable
fun autoReadingModePreference(): Preference.PreferenceItem.SwitchPreference {
    val context = LocalContext.current
    return Preference.PreferenceItem.SwitchPreference(
        preference = remember { context.harukaGraph.harukaPreferences.autoReadingMode },
        title = stringResource(HR.strings.pref_auto_reading_mode),
        subtitle = stringResource(HR.strings.pref_auto_reading_mode_summary),
    )
}
