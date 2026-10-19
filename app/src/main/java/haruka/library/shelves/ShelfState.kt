package haruka.library.shelves

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import eu.kanade.tachiyomi.ui.library.LibraryItem
import haruka.library.Shelves
import haruka.library.buildShelves
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tachiyomi.domain.category.model.Category

/** The shelves and what the rows need to draw and act on them. [shelves] is null when they hide. */
internal class ShelfData(
    val shelves: Shelves?,
    val items: Map<Long, LibraryItem>,
    /** The first category each series was found in, for selection. */
    val categories: Map<Long, Category>,
)

// The last result and the lists it came from. Reused when the library screen comes back with the same library,
// so the grid never shows a frame without the shelves it scrolled past.
private var lastShelves: Pair<List<List<LibraryItem>>, ShelfData>? = null

/**
 * Shelves of the whole library, every category's items included, computed on [Dispatchers.Default] whenever the
 * library changes. Null until the first result.
 */
@Composable
internal fun rememberShelfData(categories: List<Category>, lists: List<List<LibraryItem>>): State<ShelfData?> {
    val cached = remember { lastShelves?.takeIf { it.first == lists }?.second }
    return produceState(cached, lists) {
        if (value != null && lastShelves?.first == lists) return@produceState
        val data = withContext(Dispatchers.Default) {
            val items = LinkedHashMap<Long, LibraryItem>()
            val owners = HashMap<Long, Category>()
            categories.zip(lists).forEach { (category, list) ->
                list.forEach { item ->
                    if (items.putIfAbsent(item.id, item) == null) owners[item.id] = category
                }
            }
            ShelfData(
                shelves = buildShelves(items.values.map { it.toShelfInput() }, System.currentTimeMillis()),
                items = items,
                categories = owners,
            )
        }
        lastShelves = lists to data
        value = data
    }
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
