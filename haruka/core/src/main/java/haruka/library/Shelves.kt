package haruka.library

/** The shelves above the Collection, in the order they run top to bottom. */
enum class ShelfKind {
    /** Started series with unread chapters, most recently read first. The carousel; no "See all". */
    ContinueReading,

    /** Unread chapters fetched within [NEW_CHAPTERS_WINDOW_MILLIS], most recently fetched first. */
    NewChapters,

    /** Series without a read chapter, most recently added first. */
    NotStarted,

    /** Series whose source is unavailable (not installed, not loaded or obsolete), in library order. */
    NeedsNewSource,

    /** Series whose upload count is inflated by duplicate copies or a bogus chapter number, in library order. */
    InflatedCounts,
    ;

    val cap: Int get() = if (this == ContinueReading) CONTINUE_READING_CAP else SHELF_CAP

    /** Whether [entry] qualifies for this shelf, before caps and before shelves above it take their share. */
    fun includes(entry: ShelfInput, nowMillis: Long): Boolean = when (this) {
        ContinueReading -> entry.hasStarted && entry.unreadCount > 0
        NewChapters ->
            entry.unreadCount > 0 &&
                (entry.chapterFetchedAt ?: Long.MIN_VALUE) > nowMillis - NEW_CHAPTERS_WINDOW_MILLIS
        NotStarted -> !entry.hasStarted
        NeedsNewSource -> entry.sourceUnavailable
        InflatedCounts -> entry.countsInflated
    }

    // Newest first; null keeps library order (and sorts after dated entries)
    internal fun orderKey(entry: ShelfInput): Long? = when (this) {
        ContinueReading -> entry.lastRead
        NewChapters -> entry.chapterFetchedAt
        NotStarted -> entry.favoriteAt
        NeedsNewSource, InflatedCounts -> null
    }
}

/** One shelf: the entries it shows, and how many qualified for it once the shelves above took theirs. */
data class Shelf(val kind: ShelfKind, val ids: List<Long>, val total: Int)

const val MIN_LIBRARY_FOR_SHELVES = 20
const val CONTINUE_READING_CAP = 10
const val SHELF_CAP = 15
const val NEW_CHAPTERS_WINDOW_MILLIS = 7L * 24 * 60 * 60 * 1000

/**
 * Builds the shelves from the whole library, after filters. Entries are made distinct by id first, since a series in
 * several categories appears once per category. Shelves run top to bottom and an entry shown on one is skipped by the
 * ones below, though it always stays in the Collection. A shelf turned off takes nothing, so its series stay free for
 * the shelves below. Ties keep the order of [library].
 *
 * @param enabled the shelves the user shows.
 * @return the non-empty shelves in order; empty when they hide (fewer than [MIN_LIBRARY_FOR_SHELVES] series, or
 *   nothing to show).
 */
fun buildShelves(
    library: List<ShelfInput>,
    nowMillis: Long,
    enabled: Set<ShelfKind> = ShelfKind.entries.toSet(),
): List<Shelf> {
    val entries = library.distinctBy { it.id }
    if (entries.size < MIN_LIBRARY_FOR_SHELVES) return emptyList()
    val placed = HashSet<Long>()
    return ShelfKind.entries.filter { it in enabled }.mapNotNull { kind ->
        val candidates = entries
            .filter { it.id !in placed && kind.includes(it, nowMillis) }
            // Stable sort: unknown dates last, ties in library order
            .sortedWith(compareByDescending<ShelfInput, Long?>(nullsFirst()) { kind.orderKey(it) })
        val ids = candidates.take(kind.cap).map { it.id }
        placed += ids
        if (ids.isEmpty()) null else Shelf(kind, ids, candidates.size)
    }
}

/**
 * What the Collection shows while a shelf's "See all" filter is on: every entry of [items] that qualifies for [kind],
 * with no cap and regardless of the other shelves, in the order of [items] so the sort and its dividers still hold.
 */
fun <T> filterToShelf(items: List<T>, kind: ShelfKind, nowMillis: Long, input: (T) -> ShelfInput): List<T> =
    items.filter { kind.includes(input(it), nowMillis) }

/**
 * Whether a series' upload count misleads: at least [INFLATION_FACTOR] times its logical chapters and at least
 * [MIN_INFLATION_EXTRA] more (a 3-chapter series with one repost is not worth a shelf), or a chapter number that
 * is really an id, timestamp or year ([hasOutlierNumber]).
 */
fun isCountInflated(uploads: Int, chapters: Int, hasOutlierNumber: Boolean): Boolean = hasOutlierNumber ||
    (chapters > 0 && uploads >= INFLATION_FACTOR * chapters && uploads - chapters >= MIN_INFLATION_EXTRA)

const val INFLATION_FACTOR = 1.5
const val MIN_INFLATION_EXTRA = 10
