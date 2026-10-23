package haruka.library

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A shelf the user defines by a library search query, shown below the built-in shelves. */
@Serializable
data class CustomShelf(val id: String, val name: String, val query: String)

/**
 * Custom shelves as stored in one string preference: a JSON list of `{id, name, query}` in the user's order. The
 * id is a stable uuid string, so a rename or a query edit keeps the shelf's place and any "See all" filter on it.
 */
object CustomShelves {

    private val json = Json { ignoreUnknownKeys = true }

    /** Unreadable text gives no shelves; entries without an id or name are dropped, a repeated id keeps the first. */
    fun decode(text: String): List<CustomShelf> {
        if (text.isBlank()) return emptyList()
        val decoded = try {
            json.decodeFromString<List<CustomShelf>>(text)
        } catch (_: IllegalArgumentException) {
            // SerializationException is an IllegalArgumentException.
            return emptyList()
        }
        return decoded
            .map { CustomShelf(it.id, it.name.trim(), it.query.trim()) }
            .filter { it.id.isNotBlank() && it.name.isNotEmpty() }
            .distinctBy { it.id }
    }

    fun encode(shelves: List<CustomShelf>): String = json.encodeToString(shelves)

    /** Replaces the shelf with [shelf]'s id in place, or adds it at the end. A blank name changes nothing. */
    fun save(shelves: List<CustomShelf>, shelf: CustomShelf): List<CustomShelf> {
        val trimmed = CustomShelf(shelf.id, shelf.name.trim(), shelf.query.trim())
        if (trimmed.name.isEmpty()) return shelves
        val index = shelves.indexOfFirst { it.id == shelf.id }
        return if (index == -1) shelves + trimmed else shelves.toMutableList().apply { set(index, trimmed) }
    }

    fun delete(shelves: List<CustomShelf>, id: String): List<CustomShelf> = shelves.filterNot { it.id == id }

    /** Swaps the shelf with its neighbour [by] places away (-1 up, 1 down); unchanged at either end. */
    fun move(shelves: List<CustomShelf>, id: String, by: Int): List<CustomShelf> {
        val from = shelves.indexOfFirst { it.id == id }
        val to = from + by
        if (from == -1 || to !in shelves.indices) return shelves
        return shelves.toMutableList().apply { set(from, get(to)).also { set(to, it) } }
    }
}

/** What the library is grouped by into generated shelves, below the built-in and custom ones. */
enum class LibraryGroupBy { NONE, SOURCE, STATUS, TRACKER, AUTHOR }

/**
 * A custom or generated shelf. [key] identifies it for "See all" and as a list key; [ids] is every member in
 * library order, uncapped, so the row takes the first [SHELF_CAP] and "See all" filters to the whole set.
 */
class NamedShelf(val key: String, val name: String, val ids: List<Long>)

/**
 * Collects the members of generated shelves, then orders them largest first and by name. A series may sit in
 * several groups (one per author or tracker), but only once in each.
 */
class ShelfGroups {
    private class Group(val name: String) {
        val ids = ArrayList<Long>()
    }

    private val groups = HashMap<String, Group>()

    /** Puts [id] in the group [key], made with [name] the first time the key is seen. Add one series at a time. */
    fun add(key: String, name: String, id: Long) {
        val ids = groups.getOrPut(key) { Group(name) }.ids
        if (ids.lastOrNull() != id) ids += id
    }

    /**
     * The groups with at least [minSize] members, by size descending, then [names] order, then key so equal names
     * still come out in a fixed order. [keyPrefix] makes the keys unique among all shelves.
     */
    fun build(keyPrefix: String, minSize: Int, names: Comparator<String>): List<NamedShelf> = groups.entries
        .filter { it.value.ids.size >= minSize }
        .sortedWith(
            compareByDescending<Map.Entry<String, Group>> { it.value.ids.size }
                .thenBy(names) { it.value.name }
                .thenBy { it.key },
        )
        .map { (key, group) -> NamedShelf("$keyPrefix$key", group.name, group.ids) }
}
