package haruka.storage

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import haruka.chapter.dedup.DedupSurfaces
import haruka.chapter.dedup.toDedupChapter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.backup.repository.RestoreRepository
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.interactor.GetMangaByUrlAndSourceId
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.StorageManager

/**
 * Scans the downloads folder (see [DownloadsScanner]), sorts every series folder into in library, not in library and
 * unrecognised, and deletes through [DownloadManager] only so its download cache stays in step. Every deletion is
 * followed by a fresh scan. The scan runs in [viewModelScope], so leaving the screen cancels it.
 */
@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class StorageManagerViewModel(
    private val scanner: DownloadsScanner,
    private val storageManager: StorageManager,
    private val sourceManager: SourceManager,
    private val provider: DownloadProvider,
    private val downloadManager: DownloadManager,
    private val downloadPreferences: DownloadPreferences,
    private val getFavorites: GetFavorites,
    private val getMangaByUrlAndSourceId: GetMangaByUrlAndSourceId,
    private val restoreRepository: RestoreRepository,
    private val chapterRepository: ChapterRepository,
    private val categoryRepository: CategoryRepository,
    private val dedupSurfaces: DedupSurfaces,
) : ViewModel() {

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** The manga owning each deletable series folder, by id, from the last scan. */
    private var owners: Map<Long, Manga> = emptyMap()

    private var readPlan: ReadPlan? = null
    private var job: Job? = null

    init {
        scan()
    }

    private fun scan() {
        job?.cancel()
        readPlan = null
        job = viewModelScope.launch(Dispatchers.IO) {
            _state.value = State(phase = Phase.SCANNING)
            try {
                runScan()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                logcat(LogPriority.ERROR, e) { "Downloads storage scan failed" }
                _state.update { it.copy(phase = Phase.FAILED) }
            }
        }
    }

    private suspend fun runScan() {
        val root = storageManager.getDownloadsDirectory()
        val folders = root?.let { dir ->
            scanner.scan(dir) { done, total -> _state.update { it.copy(scanned = done, toScan = total) } }
        }
        if (folders == null) {
            _state.update { it.copy(phase = Phase.FAILED) }
            return
        }
        _state.update { it.copy(phase = Phase.MATCHING) }

        val sources = (sourceManager.getOnlineSources() + sourceManager.getStubSources())
            .map { KnownSource(it.id, provider.getSourceDirName(it)) }
        val favorites = getFavorites.await()
        val favoritesMatcher = DownloadMatcher(sources, favorites.map { it.toKnown() })
        val others = nonLibraryManga(sourcesToResolve(folders, favoritesMatcher), favorites)
        val all = favorites + others
        val series = classifyFolders(folders, DownloadMatcher(sources, all.map { it.toKnown() }))

        val ownerIds = series.mapNotNullTo(HashSet()) { it.owner?.id }
        owners = all.filter { it.id in ownerIds }.associateBy { it.id }
        _state.update { it.copy(phase = Phase.READY, summary = summarize(series, LARGEST_COUNT)) }
    }

    /**
     * The non-library manga of [sourceIds]. The manga repository has no listing by source, so this takes every url of
     * those sources from one query and loads each non-library one by its (source, url) index.
     */
    private suspend fun nonLibraryManga(sourceIds: Set<Long>, favorites: List<Manga>): List<Manga> {
        if (sourceIds.isEmpty()) return emptyList()
        val favoriteUrls = favorites.filter { it.source in sourceIds }.groupBy({ it.source }, { it.url })
            .mapValues { it.value.toHashSet() }
        val urlsBySource = restoreRepository.getMangaUrlsBySourceId()
        val result = ArrayList<Manga>()
        for (sourceId in sourceIds) {
            val known = favoriteUrls[sourceId].orEmpty()
            for (url in urlsBySource[sourceId].orEmpty()) {
                if (url in known) continue
                getMangaByUrlAndSourceId.await(url, sourceId)?.takeUnless { it.favorite }?.let(result::add)
            }
        }
        return result
    }

    private fun Manga.toKnown() = KnownManga(id, source, provider.getMangaDirName(title), favorite)

    fun deleteSeries(series: StoredSeries) {
        val manga = series.owner?.let { owners[it.id] } ?: return
        delete { downloadManager.deleteManga(manga, sourceManager.getOrStub(manga.source)) }
    }

    fun removeNotInLibrary() {
        val manga = state.value.summary?.notInLibrary.orEmpty().mapNotNull { series ->
            series.owner?.let { owners[it.id] }
        }
        if (manga.isEmpty()) return
        delete {
            for (entry in manga) downloadManager.deleteManga(entry, sourceManager.getOrStub(entry.source))
        }
    }

    /** Works out the read chapters of library series, with and without read copies, for the confirm dialog. */
    fun prepareReadChapters() {
        val summary = state.value.summary ?: return
        _state.update { it.copy(readChapters = null) }
        job?.cancel()
        job = viewModelScope.launch(Dispatchers.IO) {
            val plan = buildReadPlan(summary)
            readPlan = plan
            _state.update { it.copy(readChapters = ReadTotals(plan.plain.totals(), plan.withCopies.totals())) }
        }
    }

    private suspend fun buildReadPlan(summary: StorageSummary): ReadPlan {
        val library = summary.sources.flatMap { it.series }
            .filter { it.status == SeriesStatus.IN_LIBRARY && it.folder.chapters.isNotEmpty() }
        val ids = library.mapNotNull { it.owner?.id }
        if (ids.isEmpty()) return ReadPlan(emptyList(), emptyList())

        val chaptersByManga = chapterRepository.getChaptersByMangaIds(ids)
        val excluded = downloadPreferences.removeExcludeCategories.get().mapTo(HashSet()) { it.toLong() }
        val categoriesByManga = if (excluded.isEmpty()) emptyMap() else categoryRepository.getCategoriesByMangaIds(ids)
        val keepBookmarked = !downloadPreferences.removeBookmarkedChapters.get()

        val plain = ArrayList<SeriesDeletion>()
        val withCopies = ArrayList<SeriesDeletion>()
        for (series in library) {
            val manga = series.owner?.let { owners[it.id] } ?: continue
            val chapters = chaptersByManga[manga.id].orEmpty()
            val downloads = matchDownloads(series.folder.chapters, chapters, { it.id }) {
                provider.getValidChapterDirNames(it.name, it.scanlator, it.url)
            }
            if (downloads.isEmpty()) continue
            val config = dedupSurfaces.config(manga)
            val cleanup = ReadCleanup(
                keepBookmarked = keepBookmarked,
                inExcludedCategory = inExcludedCategory(
                    categoriesByManga[manga.id].orEmpty().map { it.id },
                    excluded,
                ),
                readInAnotherVersion = false,
                mergeSplitChapters = config.options.mergeSplitChapters,
            )
            val dedupChapters = chapters.map { it.toDedupChapter() }
            val read = chapters.mapNotNullTo(HashSet()) { if (it.read) it.id else null }
            val bookmarked = chapters.mapNotNullTo(HashSet()) { if (it.bookmark) it.id else null }
            fun deletion(cleanup: ReadCleanup): SeriesDeletion? {
                val ids = readChaptersToDelete(dedupChapters, read, bookmarked, downloads.keys, cleanup)
                if (ids.isEmpty()) return null
                // Two chapters can resolve to one legacy-named entry; it is freed once.
                val size = ids.mapNotNullTo(HashSet()) { downloads[it] }.sumOf { it.size }
                return SeriesDeletion(manga, chapters.filter { it.id in ids }, size)
            }
            deletion(cleanup)?.let(plain::add)
            // A series with grouping off treats its copies as different chapters, so no copy counts as read there.
            deletion(cleanup.copy(readInAnotherVersion = config.groupChapters))?.let(withCopies::add)
        }
        return ReadPlan(plain, withCopies)
    }

    fun deleteReadChapters(includeReadCopies: Boolean) {
        val plan = readPlan ?: return
        val deletions = if (includeReadCopies) plan.withCopies else plan.plain
        if (deletions.isEmpty()) return
        delete {
            for (deletion in deletions) {
                val source = sourceManager.getOrStub(deletion.manga.source)
                downloadManager.deleteChapters(deletion.chapters, deletion.manga, source)
            }
        }
    }

    private fun delete(block: suspend () -> Unit) {
        job?.cancel()
        job = viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(phase = Phase.DELETING) }
            try {
                block()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                logcat(LogPriority.ERROR, e) { "Downloads storage deletion failed" }
            }
            scan()
        }
    }

    private class ReadPlan(val plain: List<SeriesDeletion>, val withCopies: List<SeriesDeletion>)

    private class SeriesDeletion(
        val manga: Manga,
        val chapters: List<Chapter>,
        val size: Long,
    )

    private fun List<SeriesDeletion>.totals() = Totals(sumOf { it.chapters.size }, sumOf { it.size })

    enum class Phase { SCANNING, MATCHING, READY, DELETING, FAILED }

    @Immutable
    data class Totals(val count: Int, val size: Long)

    @Immutable
    data class ReadTotals(val plain: Totals, val withCopies: Totals)

    @Immutable
    data class State(
        val phase: Phase = Phase.SCANNING,
        val scanned: Int = 0,
        val toScan: Int = 0,
        val summary: StorageSummary? = null,
        /** Null while [prepareReadChapters] runs. */
        val readChapters: ReadTotals? = null,
    )
}

private const val LARGEST_COUNT = 10
