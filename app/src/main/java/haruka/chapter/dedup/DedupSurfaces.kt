package haruka.chapter.dedup

import dev.zacsweers.metro.Inject
import eu.kanade.domain.chapter.interactor.SetReadStatus
import eu.kanade.tachiyomi.data.download.DownloadManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
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
}

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
