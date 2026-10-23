package haruka.library.shelves

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.ui.library.LibraryItem
import haruka.chapter.dedup.DedupGraph
import haruka.di.harukaGraph
import haruka.i18n.HR
import haruka.library.CustomShelf
import haruka.library.LibraryGroupBy
import haruka.library.MIN_LIBRARY_FOR_SHELVES
import haruka.library.NamedShelf
import haruka.library.Shelf
import haruka.library.ShelfKind
import haruka.library.buildShelves
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import mihon.app.di.appGraph
import tachiyomi.core.common.util.lang.compareToWithCollator
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.track.model.Track
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

/**
 * The shelves and what the rows need to draw and act on them. [shelves] and [named] are empty when they hide.
 */
internal class ShelfData(
    val shelves: List<Shelf>,
    /** Custom shelves, then generated ones, each with every member; the rows cap them like [shelves]. */
    val named: List<NamedShelf>,
    val items: Map<Long, LibraryItem>,
    /** The first category each series was found in, for selection. */
    val categories: Map<Long, Category>,
    /** Sources that are unavailable, as the "Needs a new source" shelf and filter define it. */
    val unavailableSources: Set<Long>,
    /** Series with inflated upload counts, as the "Inflated counts" shelf and filter define it. */
    val inflated: Set<Long>,
)

private data class ShelfKey(
    val lists: List<List<LibraryItem>>,
    val enabled: Set<ShelfKind>,
    val unavailableSources: Set<Long>,
    val inflated: Set<Long>,
    val custom: List<CustomShelf>,
    val groupBy: LibraryGroupBy,
    val labels: GroupLabels,
    /** Tracks per series, only while grouping by tracker; empty otherwise so track changes don't recompute. */
    val tracks: Map<Long, List<Track>>,
)

// The last result and what it came from. Reused when the library screen comes back with the same library,
// so the grid never shows a frame without the shelves it scrolled past.
private var lastShelves: Pair<ShelfKey, ShelfData>? = null

private val NameCollator = Comparator<String> { a, b -> a.compareToWithCollator(b) }

/**
 * Shelves of the whole library, every category's items included, computed on [Dispatchers.Default] whenever the
 * library, the shown shelves, the custom shelves, the grouping or source availability change. Null until the first
 * result.
 */
@Composable
internal fun rememberShelfData(categories: List<Category>, lists: List<List<LibraryItem>>): State<ShelfData?> {
    val enabled = rememberEnabledShelves()
    val context = LocalContext.current
    val preferences = remember { context.harukaGraph.harukaPreferences }
    val custom by remember { preferences.customShelves }.collectAsState()
    val groupBy by remember { preferences.libraryGroupBy }.collectAsState()
    val labels = rememberGroupLabels()
    val tracks = rememberTracks(groupBy == LibraryGroupBy.TRACKER)
    val sourceIds = remember(lists) {
        HashSet<Long>().apply { lists.forEach { list -> list.mapTo(this) { it.libraryManga.manga.source } } }
    }
    // Seeded from the last result so a returning screen doesn't recompute (or flash) before availability arrives
    val unavailableFlow = remember(sourceIds) { context.unavailableSources(sourceIds).flowOn(Dispatchers.IO) }
    val unavailable = unavailableFlow.collectAsState(lastShelves?.first?.unavailableSources).value
    val inflated = rememberInflated(ShelfKind.InflatedCounts in enabled)
    val key = if (unavailable == null || tracks == null) {
        null
    } else {
        ShelfKey(lists, enabled, unavailable, inflated, custom, groupBy, labels, tracks)
    }
    val cached = remember { lastShelves?.takeIf { it.first == key }?.second }
    return produceState(cached, key) {
        if (key == null || lastShelves?.first == key) {
            lastShelves?.takeIf { it.first == key }?.let { value = it.second }
            return@produceState
        }
        val sourceManager = context.shelvesGraph.sourceManager
        val data = withContext(Dispatchers.Default) {
            val items = LinkedHashMap<Long, LibraryItem>()
            val owners = HashMap<Long, Category>()
            categories.zip(lists).forEach { (category, list) ->
                list.forEach { item ->
                    if (items.putIfAbsent(item.id, item) == null) owners[item.id] = category
                }
            }
            val inputs = items.values.map { it.toShelfInput(key.unavailableSources, key.inflated) }
            val shelves = buildShelves(inputs, System.currentTimeMillis(), key.enabled)
            // One lookup per source, not per series; only the source grouping shows the names
            val sourceNames = if (key.groupBy == LibraryGroupBy.SOURCE) {
                sourceIds.associateWith { sourceManager.getOrStub(it).name }
            } else {
                emptyMap()
            }
            // The same minimum as the built-in shelves, so a small library shows none of them
            val named = if (items.size < MIN_LIBRARY_FOR_SHELVES) {
                emptyList()
            } else {
                buildNamedShelves(
                    items = items.values,
                    custom = key.custom,
                    groupBy = key.groupBy,
                    labels = key.labels,
                    tracks = key.tracks,
                    sourceNames = sourceNames,
                    names = NameCollator,
                )
            }
            ShelfData(
                shelves = shelves,
                named = named,
                items = items,
                categories = owners,
                unavailableSources = key.unavailableSources,
                inflated = key.inflated,
            )
        }
        lastShelves = key to data
        value = data
    }
}

/** Tracks per series while [needed], null until loaded; otherwise empty so no track query runs. */
@Composable
private fun rememberTracks(needed: Boolean): Map<Long, List<Track>>? {
    if (!needed) return emptyMap()
    val context = LocalContext.current
    val flow = remember { context.shelvesGraph.getTracksPerManga.subscribe().flowOn(Dispatchers.IO) }
    val tracks by flow.collectAsState(lastShelves?.first?.takeIf { it.groupBy == LibraryGroupBy.TRACKER }?.tracks)
    return tracks
}

/** Names of status and tracker shelves, in the current locale. */
@Composable
private fun rememberGroupLabels(): GroupLabels {
    val context = LocalContext.current
    val ongoing = stringResource(MR.strings.ongoing)
    val completed = stringResource(MR.strings.completed)
    val licensed = stringResource(MR.strings.licensed)
    val finished = stringResource(MR.strings.publishing_finished)
    val cancelled = stringResource(MR.strings.cancelled)
    val hiatus = stringResource(MR.strings.on_hiatus)
    val unknown = stringResource(MR.strings.unknown)
    val notTracked = stringResource(HR.strings.library_group_not_tracked)
    return remember(ongoing, completed, licensed, finished, cancelled, hiatus, unknown, notTracked) {
        GroupLabels(
            statuses = mapOf(
                SManga.ONGOING.toLong() to ongoing,
                SManga.COMPLETED.toLong() to completed,
                SManga.LICENSED.toLong() to licensed,
                SManga.PUBLISHING_FINISHED.toLong() to finished,
                SManga.CANCELLED.toLong() to cancelled,
                SManga.ON_HIATUS.toLong() to hiatus,
            ),
            unknownStatus = unknown,
            trackers = context.shelvesGraph.trackerManager.trackers.associate { it.id to it.name },
            notTracked = notTracked,
        )
    }
}

/** Series with inflated upload counts, as [haruka.library.LibraryChapterCounts] found them while counting the library. */
@Composable
private fun rememberInflated(shown: Boolean): Set<Long> {
    val context = LocalContext.current
    val store = remember { (context.appGraph as DedupGraph).libraryChapterCounts }
    val ids by store.inflated.collectAsState()
    return if (shown) ids else emptySet()
}

/**
 * Where the reader stopped in each carousel series, loaded off the main thread from history. Keyed on the items, so
 * it reloads when their last read time changes after a reading session.
 */
@Composable
internal fun rememberReadingPoints(items: List<LibraryItem>): State<Map<Long, ReadingPoint?>> {
    val context = LocalContext.current
    return produceState(emptyMap(), items) {
        value = withContext(Dispatchers.IO) { items.associate { it.id to context.readingPoint(it.id) } }
    }
}
