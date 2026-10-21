package haruka.library.shelves

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import eu.kanade.tachiyomi.ui.library.LibraryItem
import haruka.chapter.dedup.DedupGraph
import haruka.library.Shelf
import haruka.library.ShelfKind
import haruka.library.buildShelves
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import mihon.app.di.appGraph
import tachiyomi.domain.category.model.Category

/** The shelves and what the rows need to draw and act on them. [shelves] is empty when they hide. */
internal class ShelfData(
    val shelves: List<Shelf>,
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
)

// The last result and what it came from. Reused when the library screen comes back with the same library,
// so the grid never shows a frame without the shelves it scrolled past.
private var lastShelves: Pair<ShelfKey, ShelfData>? = null

/**
 * Shelves of the whole library, every category's items included, computed on [Dispatchers.Default] whenever the
 * library, the shown shelves or source availability change. Null until the first result.
 */
@Composable
internal fun rememberShelfData(categories: List<Category>, lists: List<List<LibraryItem>>): State<ShelfData?> {
    val enabled = rememberEnabledShelves()
    val context = LocalContext.current
    val sourceIds = remember(lists) { lists.flatMapTo(HashSet()) { list -> list.map { it.libraryManga.manga.source } } }
    // Seeded from the last result so a returning screen doesn't recompute (or flash) before availability arrives
    val unavailableFlow = remember(sourceIds) { context.unavailableSources(sourceIds).flowOn(Dispatchers.IO) }
    val unavailable by unavailableFlow.collectAsState(lastShelves?.first?.unavailableSources)
    val inflated = rememberInflated(ShelfKind.InflatedCounts in enabled)
    val key = unavailable?.let { ShelfKey(lists, enabled, it, inflated) }
    val cached = remember { lastShelves?.takeIf { it.first == key }?.second }
    return produceState(cached, key) {
        if (key == null || lastShelves?.first == key) {
            lastShelves?.takeIf { it.first == key }?.let { value = it.second }
            return@produceState
        }
        val data = withContext(Dispatchers.Default) {
            val items = LinkedHashMap<Long, LibraryItem>()
            val owners = HashMap<Long, Category>()
            categories.zip(lists).forEach { (category, list) ->
                list.forEach { item ->
                    if (items.putIfAbsent(item.id, item) == null) owners[item.id] = category
                }
            }
            val inputs = items.values.map { it.toShelfInput(key.unavailableSources, key.inflated) }
            ShelfData(
                shelves = buildShelves(inputs, System.currentTimeMillis(), key.enabled),
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
