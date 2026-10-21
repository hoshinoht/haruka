package haruka.chapter.ui

import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.ui.manga.ChapterList
import eu.kanade.tachiyomi.ui.manga.MangaViewModel
import haruka.chapter.dedup.ChapterDeduper
import haruka.chapter.dedup.DedupOptions
import haruka.chapter.dedup.DedupSettings
import haruka.chapter.dedup.DedupSurfaces
import haruka.chapter.dedup.EffectiveDedupConfig
import haruka.chapter.dedup.OfficialCatalogLoader
import haruka.chapter.dedup.nextUnreadChapterId
import haruka.chapter.dedup.toDedupChapter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager

/** What the manga screen's chapter list needs to group; absent from the state while grouping is off. */
data class ChapterGroupsInput(
    val options: DedupOptions,
    val expanded: Set<Long>,
    val expansion: ChapterGroupExpansion,
    val deduper: ChapterDeduper,
) {
    /** Groups the filtered and sorted [chapters]; a copy counts as downloaded from its row's download state. */
    fun build(chapters: List<ChapterList.Item>): GroupedChapterList {
        val items = HashMap<Long, ChapterList.Item>(chapters.size * 2)
        val dedupChapters = chapters.map { item ->
            items[item.id] = item
            item.chapter.toDedupChapter()
        }
        val groups = buildChapterGroups(dedupChapters, deduper, options) { items.getValue(it.id).isDownloaded }
        val rows = groups.toRows(expanded, expansion) { id, row ->
            val item = items.getValue(id)
            if (row == null) item else item.copy(harukaRow = row)
        }
        val selectable = ArrayList<ChapterList.Item>(rows.size)
        selectable += rows
        for (group in groups) {
            if (group.winner.size < 2 || group.key in expanded) continue
            for (index in 1..<group.winner.size) selectable += items.getValue(group.winner[index])
        }
        return GroupedChapterList(groups, rows, selectable)
    }
}

class GroupedChapterList(
    val groups: List<ChapterGroup>,
    /** The rows shown, in list order. */
    val rows: List<ChapterList.Item>,
    /** [rows] plus the hidden parts of collapsed winners, which selecting their head selects too. */
    val selectable: List<ChapterList.Item>,
) {
    val groupsByHead: Map<Long, ChapterGroup> by lazy {
        groups.filter { it.isGrouped }.associateBy { it.head }
    }
}

/** Chapter grouping and duplicate handling for one manga screen; a new instance per ViewModel. */
@Inject
class MangaChapterGroups(
    private val dedupSettings: DedupSettings,
    private val catalogLoader: OfficialCatalogLoader,
    private val getManga: GetManga,
    private val sourceManager: SourceManager,
    private val surfaces: DedupSurfaces,
) {

    private val expansion = ChapterGroupExpansion()
    private val deduper by lazy { ChapterDeduper(catalogLoader.catalog) }

    @Volatile
    private var config: EffectiveDedupConfig? = null

    /** Adds the grouping input to each state while grouping is on for the manga. */
    fun attach(
        mangaId: Long,
        states: Flow<MangaViewModel.State.Success>,
    ): Flow<MangaViewModel.State.Success> {
        val configs = flow { emit(getManga.await(mangaId)) }
            .flatMapLatest { manga ->
                dedupSettings.config(mangaId, manga?.let { sourceManager.getOrStub(it.source).name }.orEmpty())
            }
            .onEach { config = it }
        return combine(states, configs, expansion.expanded) { state, config, expanded ->
            if (!config.groupChapters) return@combine state
            state.copy(harukaGroups = ChapterGroupsInput(config.options, expanded, expansion, deduper))
        }
    }

    /**
     * The chapters a mark read/unread of [chapters] covers: a group head row stands for every copy of its chapter,
     * and finishing a chapter marks its other copies read when the user wants that.
     */
    fun withCopies(state: MangaViewModel.State.Success?, chapters: List<Chapter>, read: Boolean): List<Chapter> {
        val config = config
        if (state == null || config == null) return chapters
        val ids = state.harukaGroupedList
            ?.let { withGroupCopies(chapters.map { it.id }, it.groupsByHead) }
            ?: chapters.mapTo(LinkedHashSet()) { it.id }
        val all = state.chapters.map { it.chapter }
        val result = LinkedHashMap<Long, Chapter>()
        chapters.forEach { result[it.id] = it }
        if (ids.size > chapters.size) all.forEach { if (it.id in ids) result.putIfAbsent(it.id, it) }
        if (read) surfaces.copiesToMarkRead(config, all, ids).forEach { result.putIfAbsent(it.id, it) }
        return result.values.toList()
    }

    /** [items] with each group head replaced by all of its winner's parts, so download actions cover them all. */
    fun withWinnerParts(
        state: MangaViewModel.State.Success?,
        items: List<ChapterList.Item>,
    ): List<ChapterList.Item> {
        val grouped = state?.harukaGroupedList ?: return items
        val byId = state.chapters.associateBy { it.id }
        val result = LinkedHashMap<Long, ChapterList.Item>()
        for (item in items) {
            result.putIfAbsent(item.id, item)
            val group = grouped.groupsByHead[item.id] ?: continue
            group.winner.forEach { id -> byId[id]?.let { result.putIfAbsent(id, it) } }
        }
        return if (result.size == items.size) items else result.values.toList()
    }

    suspend fun withoutDownloadedCopies(manga: Manga, chapters: List<Chapter>): List<Chapter> =
        surfaces.withoutDownloadedCopies(manga, chapters)
}

/**
 * The chapter to open for a tap on [chapter]. A head row standing for a chapter split into parts opens its first
 * unread part, so reading resumes where it stopped (parts 1 and 2 read: part 3); with every part read it opens part 1.
 * Any other row opens itself.
 */
fun resumePart(state: MangaViewModel.State.Success, chapter: Chapter): Chapter {
    val group = state.harukaGroupedList?.groupsByHead?.get(chapter.id) ?: return chapter
    if (group.winner.size < 2) return chapter
    val byId = state.chapters.associateBy({ it.id }, { it.chapter })
    val parts = group.winner.mapNotNull(byId::get)
    return parts.firstOrNull { !it.read } ?: parts.firstOrNull() ?: chapter
}

/**
 * The chapter Start reading opens while grouping is on: the next logical chapter not read in any copy, at its
 * preferred copy (a split one at its first unread part), in the list's filters and order. Null when all are read.
 */
fun nextUnreadGrouped(state: MangaViewModel.State.Success, input: ChapterGroupsInput): Chapter? {
    val items = state.processedChapters
    val byId = items.associateBy { it.id }
    val deduped = input.deduper.dedupe(items.map { it.chapter.toDedupChapter() }, input.options) {
        byId.getValue(it.id).isDownloaded
    }
    val id = nextUnreadChapterId(deduped, state.manga.sortDescending()) { byId.getValue(it).chapter.read }
    return id?.let { byId.getValue(it).chapter }
}
