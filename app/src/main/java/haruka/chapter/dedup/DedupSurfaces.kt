package haruka.chapter.dedup

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Inject
import eu.kanade.domain.chapter.interactor.SetReadStatus
import eu.kanade.domain.chapter.model.applyFilters
import eu.kanade.tachiyomi.data.download.DownloadManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import mihon.app.di.appGraph
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager

/**
 * Dedup for the reader, auto-download and read marking; every call reads the manga's current settings. Callers may be
 * on the main thread (the reader's progress writes are), so the work moves to IO.
 */
@Inject
class DedupSurfaces(
    private val dedupSettings: DedupSettings,
    private val catalogLoader: OfficialCatalogLoader,
    private val sourceManager: SourceManager,
    private val getChaptersByMangaId: GetChaptersByMangaId,
    private val downloadManager: DownloadManager,
    private val setReadStatus: SetReadStatus,
) {

    private val deduper by lazy { ChapterDeduper(catalogLoader.catalog) }

    suspend fun config(manga: Manga): EffectiveDedupConfig =
        dedupSettings.config(manga.id, sourceManager.getOrStub(manga.source).name).first()

    /**
     * The reader's [chapters] (in reading order) cut down to each chapter's winner, replacing upstream's
     * removeDuplicates. The chapter being read keeps its copy. A split winner's parts are put in part order, since a
     * source that numbers every part as the whole chapter leaves their order to ties in the chapter sort.
     */
    suspend fun readerChapters(manga: Manga, chapters: List<Chapter>, current: Chapter): List<Chapter> =
        withContext(Dispatchers.IO) {
            val options = config(manga).options
            val downloaded = downloadManager.getDownloadedChapterIds(chapters, manga)
            val deduped = deduper.dedupe(chapters.map { it.toDedupChapter() }, options) { it.id in downloaded }
            val keep = readerChapterIds(deduped, current.id)
            inPartOrder(chapters.filter { it.id in keep }, deduped)
        }

    /** Drops [chapters] queued automatically whose chapter already has a downloaded copy, when the user wants that. */
    suspend fun withoutDownloadedCopies(manga: Manga, chapters: List<Chapter>): List<Chapter> {
        if (chapters.isEmpty()) return chapters
        return withContext(Dispatchers.IO) {
            val config = config(manga)
            if (!config.skipDownloadedDuplicates) return@withContext chapters
            val all = getChaptersByMangaId.await(manga.id, applyScanlatorFilter = true)
            val drop = chaptersWithDownloadedCopy(
                chapters = all.map { it.toDedupChapter() },
                mergeSplitChapters = config.options.mergeSplitChapters,
                candidates = chapters.mapTo(HashSet()) { it.id },
                downloaded = downloadManager.getDownloadedChapterIds(all, manga),
            )
            if (drop.isEmpty()) chapters else chapters.filterNot { it.id in drop }
        }
    }

    /** The other copies of the chapters [read] finishes, among [chapters], when duplicates are marked read. */
    fun copiesToMarkRead(config: EffectiveDedupConfig, chapters: List<Chapter>, read: Set<Long>): List<Chapter> {
        if (!config.markDuplicatesRead || read.isEmpty()) return emptyList()
        val readIds = chapters.filter { it.read }.mapTo(HashSet()) { it.id }
        val ids = duplicateCopiesToMarkRead(
            chapters = chapters.map { it.toDedupChapter() },
            mergeSplitChapters = config.options.mergeSplitChapters,
            newlyRead = read,
            isRead = { it in readIds },
        )
        return if (ids.isEmpty()) emptyList() else chapters.filter { it.id in ids }
    }

    /** The reader finished [chapter]: marks its other copies read through upstream [SetReadStatus]. */
    suspend fun onChapterRead(manga: Manga, chapter: Chapter) = withContext(Dispatchers.IO) {
        val config = config(manga)
        if (!config.markDuplicatesRead) return@withContext
        val chapters = getChaptersByMangaId.await(manga.id, applyScanlatorFilter = true)
        val copies = copiesToMarkRead(config, chapters, setOf(chapter.id))
        if (copies.isNotEmpty()) setReadStatus.await(read = true, chapters = copies.toTypedArray())
    }

    /**
     * Continue reading from outside the manga screen: with duplicates grouped for [manga], the next logical chapter
     * at its preferred copy, as the manga screen's Start reading picks it; otherwise [upstream]'s choice.
     */
    suspend fun nextUnread(manga: Manga, upstream: suspend () -> Chapter?): Chapter? {
        val config = config(manga)
        if (!config.groupChapters) return upstream()
        return withContext(Dispatchers.IO) {
            val chapters = getChaptersByMangaId.await(manga.id, applyScanlatorFilter = true)
                .applyFilters(manga, downloadManager)
            val downloaded = downloadManager.getDownloadedChapterIds(chapters, manga)
            val deduped = deduper.dedupe(chapters.map { it.toDedupChapter() }, config.options) { it.id in downloaded }
            val byId = chapters.associateBy { it.id }
            nextUnreadChapterId(deduped, manga.sortDescending()) { byId.getValue(it).read }?.let(byId::getValue)
        }
    }

    /**
     * [manga]'s chapter counts as its chapter list shows them: with duplicates grouped, each logical chapter counts
     * once and is read when any one of its copies is fully read. Counts the chapters the library counts (scanlator
     * filter applied). [SeriesCounts.inflated] tells whether the raw upload count misleads (see
     * [haruka.library.isCountInflated]) whether or not grouping is on.
     */
    suspend fun seriesCounts(manga: Manga): SeriesCounts = withContext(Dispatchers.IO) {
        val chapters = getChaptersByMangaId.await(manga.id, applyScanlatorFilter = true)
        if (chapters.isEmpty()) return@withContext SeriesCounts(0, 0, grouped = false, inflated = false)
        val config = config(manga)
        val deduped = deduper.dedupe(chapters.map { it.toDedupChapter() }, config.options)
        val outlier = haruka.chapter.chapterNumberOutlierThreshold(chapters.map { it.chapterNumber }) != null
        val inflated = haruka.library.isCountInflated(chapters.size, deduped.size, outlier)
        if (!config.groupChapters) {
            return@withContext SeriesCounts(
                chapters.size.toLong(),
                chapters.count { it.read }.toLong(),
                grouped = false,
                inflated = inflated,
            )
        }
        val read = chapters.mapNotNullTo(HashSet()) { chapter -> chapter.id.takeIf { chapter.read } }
        val logicalRead = deduped.count { chapter ->
            chapter.logical.copies.any { copy -> copy.completes { it in read } }
        }
        SeriesCounts(deduped.size.toLong(), logicalRead.toLong(), grouped = true, inflated = inflated)
    }
}

/** One series' chapter counts (see [DedupSurfaces.seriesCounts]). */
data class SeriesCounts(val total: Long, val read: Long, val grouped: Boolean, val inflated: Boolean)

/** Haruka graph entry for upstream screens that have a Context but no injected [DedupSurfaces]. */
@ContributesTo(AppScope::class)
interface DedupGraph {
    val dedupSurfaces: DedupSurfaces
    val libraryChapterCounts: haruka.library.LibraryChapterCounts
}

/** The library's continue-reading button: see [DedupSurfaces.nextUnread]. */
suspend fun nextUnreadForLibrary(context: Context, manga: Manga, upstream: suspend () -> Chapter?): Chapter? =
    (context.appGraph as DedupGraph).dedupSurfaces.nextUnread(manga, upstream)

/** [chapters] with each split winner's parts rearranged, within the slots they already hold, into part order. */
internal fun inPartOrder(chapters: List<Chapter>, deduped: List<DedupedChapter>): List<Chapter> {
    val splits = deduped.filter { it.winner.parts.size > 1 }
    if (splits.isEmpty()) return chapters
    val result = chapters.toMutableList()
    val indexById = HashMap<Long, Int>(chapters.size * 2)
    chapters.forEachIndexed { index, chapter -> indexById[chapter.id] = index }
    val byId = chapters.associateBy { it.id }
    for (split in splits) {
        val parts = split.winner.parts.mapNotNull { byId[it.id] }
        val slots = parts.mapNotNull { indexById[it.id] }.sorted()
        if (slots.size != parts.size) continue
        slots.forEachIndexed { i, slot -> result[slot] = parts[i] }
    }
    return result
}
