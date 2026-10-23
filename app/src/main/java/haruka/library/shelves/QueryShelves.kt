package haruka.library.shelves

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.ui.library.LibraryItem
import haruka.library.CustomShelf
import haruka.library.LibraryGroupBy
import haruka.library.NamedShelf
import haruka.library.ShelfGroups
import kotlinx.datetime.LocalDate
import mihon.domain.library.model.search.AndNode
import mihon.domain.library.model.search.ComparisonField
import mihon.domain.library.model.search.ComparisonQueryNode
import mihon.domain.library.model.search.EmptyQueryNode
import mihon.domain.library.model.search.LibrarySearchLexer
import mihon.domain.library.model.search.LibrarySearchParser
import mihon.domain.library.model.search.NotNode
import mihon.domain.library.model.search.OrNode
import mihon.domain.library.model.search.QueryNode
import mihon.feature.library.matches
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.track.model.Track
import tachiyomi.source.local.isLocal

/** Why a shelf query can't be used. Mihon's parser accepts any text, so these are the cases it would get wrong. */
internal sealed interface QueryError {
    /** Nothing to search for: the shelf would hold the whole library. */
    data object Blank : QueryError

    /** A bracket without its pair; the parser would silently drop part of the query. */
    data object Parentheses : QueryError

    /** A count comparison such as `unread>x`, which never matches. */
    data class NotANumber(val term: String) : QueryError

    /** A date comparison such as `added>yesterday`, which never matches. */
    data class NotADate(val term: String) : QueryError
}

internal sealed interface ShelfQuery {
    class Valid(val node: QueryNode) : ShelfQuery
    class Invalid(val error: QueryError) : ShelfQuery
}

/**
 * Shelf queries in Mihon's library search syntax, parsed once per distinct text: every library emission and every
 * series reuse the cached tree. Bounded, since the settings dialog parses each keystroke.
 */
internal object ShelfQueries {

    private const val MAX_CACHED = 64

    private val cache = object : LinkedHashMap<String, ShelfQuery>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ShelfQuery>) = size > MAX_CACHED
    }

    fun parse(query: String): ShelfQuery = synchronized(cache) { cache.getOrPut(query) { compile(query) } }

    private fun compile(query: String): ShelfQuery {
        val tokens = LibrarySearchLexer.tokenize(query)
        var depth = 0
        for (token in tokens) {
            when (token) {
                LibrarySearchLexer.Token.LParen -> depth++
                LibrarySearchLexer.Token.RParen -> if (--depth < 0) return ShelfQuery.Invalid(QueryError.Parentheses)
                else -> Unit
            }
        }
        if (depth != 0) return ShelfQuery.Invalid(QueryError.Parentheses)
        // Lexed once: QueryNode.from would tokenize the text again
        val node = LibrarySearchParser(tokens).parse()
        if (node == EmptyQueryNode || (node is AndNode && node.children.isEmpty())) {
            return ShelfQuery.Invalid(QueryError.Blank)
        }
        return node.badValue()?.let { ShelfQuery.Invalid(it) } ?: ShelfQuery.Valid(node)
    }

    // The value checks mirror how mihon.feature.library's evaluator reads each comparison field.
    private fun QueryNode.badValue(): QueryError? = when (this) {
        is AndNode -> children.firstNotNullOfOrNull { it.badValue() }
        is OrNode -> children.firstNotNullOfOrNull { it.badValue() }
        is NotNode -> child.badValue()
        is ComparisonQueryNode -> {
            val term = "${field.aliases.first()}${queryComparator.symbol}$value"
            when (field) {
                ComparisonField.DATE_ADDED, ComparisonField.NEXT_UPDATE ->
                    QueryError.NotADate(term).takeIf { runCatching { LocalDate.parse(value) }.isFailure }
                ComparisonField.FETCH_INTERVAL -> QueryError.NotANumber(term).takeIf { value.toIntOrNull() == null }
                ComparisonField.ID, ComparisonField.UNREAD, ComparisonField.READ, ComparisonField.TOTAL ->
                    QueryError.NotANumber(term).takeIf { value.toLongOrNull() == null }
            }
        }
        else -> null
    }
}

/** Names of generated shelves that come from string resources and the tracker list, resolved before the pass. */
internal data class GroupLabels(
    /** Publication status names by `SManga` status; any other status counts as [unknownStatus]. */
    val statuses: Map<Long, String>,
    val unknownStatus: String,
    val trackers: Map<Long, String>,
    val notTracked: String,
)

internal const val CUSTOM_SHELF_KEY_PREFIX = "custom:"

/** An author gets a shelf only when it shares at least this many series; a shelf of one is just the series. */
internal const val MIN_AUTHOR_SERIES = 2

/**
 * Custom shelves in the user's order, then the [groupBy] shelves, from one pass over [items] (the unique library
 * entries). Only ids are collected; they resolve back to the same [LibraryItem]s. Shelves without members are left
 * out, as are custom shelves whose query is invalid.
 *
 * @param tracks tracks per series; read only for [LibraryGroupBy.TRACKER].
 * @param sourceNames display name of each source in [items]; read only for [LibraryGroupBy.SOURCE].
 * @param names order of generated shelves of the same size.
 */
internal fun buildNamedShelves(
    items: Collection<LibraryItem>,
    custom: List<CustomShelf>,
    groupBy: LibraryGroupBy,
    labels: GroupLabels,
    tracks: Map<Long, List<Track>>,
    sourceNames: Map<Long, String>,
    names: Comparator<String>,
): List<NamedShelf> {
    val queries = custom.mapNotNull { shelf ->
        (ShelfQueries.parse(shelf.query) as? ShelfQuery.Valid)?.let { shelf to it.node }
    }
    val members = Array(queries.size) { ArrayList<Long>() }
    val groups = ShelfGroups()
    for (item in items) {
        val id = item.id
        for (index in queries.indices) {
            if (queries[index].second.matches(item)) members[index] += id
        }
        val manga = item.libraryManga.manga
        when (groupBy) {
            LibraryGroupBy.NONE -> Unit
            LibraryGroupBy.SOURCE -> {
                val source = manga.source
                groups.add(source.toString(), sourceNames[source] ?: source.toString(), id)
            }
            LibraryGroupBy.STATUS -> {
                val name = labels.statuses[manga.status]
                if (name == null) {
                    groups.add("unknown", labels.unknownStatus, id)
                } else {
                    groups.add(manga.status.toString(), name, id)
                }
            }
            LibraryGroupBy.TRACKER -> {
                var tracked = false
                tracks[id]?.forEach { track ->
                    val name = labels.trackers[track.trackerId] ?: return@forEach
                    groups.add(track.trackerId.toString(), name, id)
                    tracked = true
                }
                if (!tracked) groups.add("none", labels.notTracked, id)
            }
            LibraryGroupBy.AUTHOR -> manga.author?.split(',')?.forEach { part ->
                val author = part.trim()
                // Case-insensitive, so "ONE" and "One" from two sources are the same author
                if (author.isNotEmpty()) groups.add(author.lowercase(), author, id)
            }
        }
    }
    val customShelves = queries.indices
        .filter { members[it].isNotEmpty() }
        .map { NamedShelf("$CUSTOM_SHELF_KEY_PREFIX${queries[it].first.id}", queries[it].first.name, members[it]) }
    if (groupBy == LibraryGroupBy.NONE) return customShelves
    val minSize = if (groupBy == LibraryGroupBy.AUTHOR) MIN_AUTHOR_SERIES else 1
    return customShelves + groups.build("group:${groupBy.name}:", minSize, names)
}

/** How many of [items] a query matches, for the settings dialog; null when it is invalid. */
internal fun countMatches(items: List<LibraryItem>, query: ShelfQuery): Int? =
    (query as? ShelfQuery.Valid)?.let { valid -> items.count { valid.node.matches(it) } }

/**
 * A series as the library screen builds it, with the fields the query evaluator reads ([LibraryItem.sourceName]
 * lowercased like `LibraryViewModel`); badges are off since nothing draws it.
 */
internal fun LibraryManga.toQueryItem(source: Source): LibraryItem = LibraryItem(
    libraryManga = this,
    downloadCount = 0,
    unreadCount = unreadCount,
    isLocal = manga.isLocal(),
    sourceName = source.name.lowercase(),
    sourceLanguage = source.lang,
    badges = LibraryItem.Badges(downloadCount = 0, unreadCount = 0, isLocal = false, sourceLanguage = ""),
)
