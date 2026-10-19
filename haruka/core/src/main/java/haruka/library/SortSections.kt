package haruka.library

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/** The library sorts that get dividers. Sorts without dividers (random, tracker score) map to [None]. */
enum class SectionSort {
    Alphabetical,
    LastRead,
    LastUpdate,
    LatestChapter,
    ChapterFetchDate,
    DateAdded,
    UnreadCount,
    TotalChapters,
    None,
}

/** What the dividers and shelves read from a library entry. Dates are epoch milliseconds; null when unknown. */
class ShelfInput(
    val id: Long,
    val title: String,
    val lastRead: Long?,
    val lastUpdate: Long?,
    val latestUpload: Long?,
    val chapterFetchedAt: Long?,
    val favoriteAt: Long?,
    val unreadCount: Long,
    val totalChapters: Long,
    /** At least one chapter is read. */
    val hasStarted: Boolean,
    /** The series' source can't be used as-is: not installed, not loaded or obsolete. */
    val sourceUnavailable: Boolean = false,
)

/** The group an entry falls in. [id] is unique among the keys of one sort. */
sealed interface SectionKey {
    val id: String
}

enum class RecencySection : SectionKey {
    Today,
    ThisWeek,
    ThisMonth,
    EarlierThisYear,
    Older,
    NeverRead,
    ;

    override val id: String get() = name
}

/** Month of [SectionSort.DateAdded], in the zone given to [SortSections]. */
data class MonthSection(val year: Int, val month: Int) : SectionKey {
    override val id: String get() = "$year-$month"
}

/** Added before favourite dates were recorded (old restored backups). */
data object UnknownDateSection : SectionKey {
    override val id: String get() = "unknown"
}

/** Titles that collate before `A`: digits and symbols. */
data object SymbolSection : SectionKey {
    override val id: String get() = "#"
}

data class LetterSection(val letter: Char) : SectionKey {
    override val id: String get() = letter.toString()
}

/** Titles that collate after every Latin letter: CJK, Hangul, Cyrillic and so on. */
data object OtherScriptSection : SectionKey {
    override val id: String get() = "other"
}

enum class UnreadSection : SectionKey {
    TenOrMore,
    OneToNine,
    CaughtUp,
    ;

    override val id: String get() = name
}

enum class ChapterCountSection : SectionKey {
    HundredOrMore,
    FiftyToNinetyNine,
    TenToFortyNine,
    UnderTen,
    ;

    override val id: String get() = name
}

/** A run of [count] consecutive entries, from index [start] of the list, that share [key]. */
data class Section(val key: SectionKey, val start: Int, val count: Int)

/**
 * Splits an already sorted library list into the groups its dividers show. Every bucket is a contiguous range of
 * the sort key, so a sorted list yields each key at most once, in either direction.
 *
 * @param now the time the relative buckets (today, this week, …) count from.
 * @param zone the zone that decides calendar days, months and years.
 * @param collator the comparator that sorted the titles, so the letter buckets follow the same order.
 */
class SortSections(
    now: Instant,
    private val zone: ZoneId,
    private val collator: Comparator<String>,
) {
    private val nowMillis = now.toEpochMilli()
    private val startOfToday: Long
    private val startOfYear: Long

    init {
        val today = now.atZone(zone).toLocalDate()
        startOfToday = today.atStartOfDay(zone).toInstant().toEpochMilli()
        startOfYear = today.withDayOfYear(1).atStartOfDay(zone).toInstant().toEpochMilli()
    }

    /** The groups of [items], in list order; empty for [SectionSort.None]. */
    fun sections(sort: SectionSort, items: List<ShelfInput>): List<Section> {
        if (sort == SectionSort.None || items.isEmpty()) return emptyList()
        val sections = ArrayList<Section>()
        var start = 0
        var key = keyOf(sort, items[0])
        for (index in 1..items.lastIndex) {
            val next = keyOf(sort, items[index])
            if (next != key) {
                sections += Section(key, start, index - start)
                start = index
                key = next
            }
        }
        sections += Section(key, start, items.size - start)
        return sections
    }

    /** The bucket of [item] under [sort], which must not be [SectionSort.None]. */
    fun keyOf(sort: SectionSort, item: ShelfInput): SectionKey = when (sort) {
        SectionSort.Alphabetical -> letter(item.title)
        SectionSort.LastRead -> recency(item.lastRead, withYear = true) ?: RecencySection.NeverRead
        SectionSort.LastUpdate -> recency(item.lastUpdate, withYear = false) ?: RecencySection.Older
        SectionSort.LatestChapter -> recency(item.latestUpload, withYear = false) ?: RecencySection.Older
        SectionSort.ChapterFetchDate -> recency(item.chapterFetchedAt, withYear = false) ?: RecencySection.Older
        SectionSort.DateAdded -> month(item.favoriteAt)
        SectionSort.UnreadCount -> when {
            item.unreadCount == 0L -> UnreadSection.CaughtUp
            item.unreadCount >= 10 -> UnreadSection.TenOrMore
            else -> UnreadSection.OneToNine
        }
        SectionSort.TotalChapters -> when {
            item.totalChapters >= 100 -> ChapterCountSection.HundredOrMore
            item.totalChapters >= 50 -> ChapterCountSection.FiftyToNinetyNine
            item.totalChapters >= 10 -> ChapterCountSection.TenToFortyNine
            else -> ChapterCountSection.UnderTen
        }
        SectionSort.None -> error("Sort without sections")
    }

    // Each bucket is a range of the timestamp, checked newest first, so buckets stay contiguous in a sorted list.
    // Times after now (clock skew) count as today rather than starting a second run.
    private fun recency(time: Long?, withYear: Boolean): RecencySection? = when {
        time == null -> null
        time >= startOfToday -> RecencySection.Today
        time > nowMillis - WEEK_MILLIS -> RecencySection.ThisWeek
        time > nowMillis - MONTH_MILLIS -> RecencySection.ThisMonth
        withYear && time >= startOfYear -> RecencySection.EarlierThisYear
        else -> RecencySection.Older
    }

    private fun month(time: Long?): SectionKey {
        if (time == null || time <= 0) return UnknownDateSection
        val date = ZonedDateTime.ofInstant(Instant.ofEpochMilli(time), zone)
        return MonthSection(date.year, date.monthValue)
    }

    // Buckets are ranges of the collation order: below "A" is #, then [L, next letter) is L. Above "Z", titles
    // starting with Z at the collator's strength stay under Z and the rest (other scripts) go to Other. A title
    // inside a letter's range that doesn't start with it (a letter the locale sorts between two Latin ones)
    // stays under that letter: anything else would split the range and repeat a divider.
    private fun letter(title: String): SectionKey {
        if (collator.compare(title, LETTERS[0]) < 0) return SymbolSection
        var low = 0
        var high = LETTERS.lastIndex
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (collator.compare(LETTERS[mid], title) <= 0) low = mid else high = mid - 1
        }
        if (low == LETTERS.lastIndex && !startsWith(title, LETTERS[low])) return OtherScriptSection
        return LETTER_SECTIONS[low]
    }

    // Whether the shortest prefix of [title] that collates at or above [letter] equals it, so ignorable leading
    // characters and accents (Ž) still count as starting with the letter.
    private fun startsWith(title: String, letter: String): Boolean {
        var end = 0
        while (end < title.length) {
            end = title.offsetByCodePoints(end, 1)
            val order = collator.compare(title.substring(0, end), letter)
            if (order >= 0) return order == 0
        }
        return false
    }

    private companion object {
        const val DAY_MILLIS = 24L * 60 * 60 * 1000
        const val WEEK_MILLIS = 7 * DAY_MILLIS
        const val MONTH_MILLIS = 30 * DAY_MILLIS
        val LETTERS = ('A'..'Z').map { it.toString() }
        val LETTER_SECTIONS = ('A'..'Z').map { LetterSection(it) }
    }
}
