package haruka.library

/** One shelf: the entries it shows, and how many qualified for it in total ("See all N"). */
data class Shelf(val ids: List<Long>, val total: Int) {
    val isEmpty: Boolean get() = ids.isEmpty()
}

/** The shelves above the Collection, top to bottom. */
data class Shelves(
    /** Started series with unread chapters, most recently read first. No "See all". */
    val continueReading: List<Long>,
    /** Unread chapters fetched within [NEW_CHAPTERS_WINDOW_MILLIS], most recently fetched first. */
    val newChapters: Shelf,
    /** Series without a read chapter, most recently added first. */
    val notStarted: Shelf,
)

const val MIN_LIBRARY_FOR_SHELVES = 20
const val CONTINUE_READING_CAP = 10
const val SHELF_CAP = 15
const val NEW_CHAPTERS_WINDOW_MILLIS = 7L * 24 * 60 * 60 * 1000

/**
 * Builds the shelves from the whole library, after filters. Entries are made distinct by id first, since a series in
 * several categories appears once per category. Shelves run top to bottom and an entry shown on one is skipped by the
 * ones below, though it always stays in the Collection. Ties keep the order of [library].
 *
 * @return null when the shelves hide: fewer than [MIN_LIBRARY_FOR_SHELVES] series, or every shelf empty.
 */
fun buildShelves(library: List<ShelfInput>, nowMillis: Long): Shelves? {
    val entries = library.distinctBy { it.id }
    if (entries.size < MIN_LIBRARY_FOR_SHELVES) return null
    val placed = HashSet<Long>()

    fun shelf(cap: Int, include: (ShelfInput) -> Boolean, newestFirst: (ShelfInput) -> Long?): Shelf {
        val candidates = entries
            .filter { it.id !in placed && include(it) }
            // Stable sort: nulls (unknown dates) last, ties in library order
            .sortedWith(compareByDescending<ShelfInput, Long?>(nullsFirst()) { newestFirst(it) })
        val ids = candidates.take(cap).map { it.id }
        placed += ids
        return Shelf(ids, candidates.size)
    }

    val continueReading = shelf(CONTINUE_READING_CAP, { it.hasStarted && it.unreadCount > 0 }) { it.lastRead }
    val newChapters = shelf(
        SHELF_CAP,
        { it.unreadCount > 0 && (it.chapterFetchedAt ?: Long.MIN_VALUE) > nowMillis - NEW_CHAPTERS_WINDOW_MILLIS },
    ) { it.chapterFetchedAt }
    val notStarted = shelf(SHELF_CAP, { !it.hasStarted }) { it.favoriteAt }

    if (continueReading.isEmpty && newChapters.isEmpty && notStarted.isEmpty) return null
    return Shelves(continueReading.ids, newChapters, notStarted)
}
