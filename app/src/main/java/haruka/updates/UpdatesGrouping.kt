package haruka.updates

import kotlinx.datetime.LocalDate

/**
 * A row of the grouped Updates list.
 *
 * Chapters are grouped per manga within a day, in the order the manga first appears that day, so the newest
 * manga stays on top and nothing moves across days.
 */
sealed interface GroupedRow<out T> {
    /** A day's heading; [chapterCount] is how many chapters arrived that day. */
    data class Header(val date: LocalDate, val chapterCount: Int) : GroupedRow<Nothing>

    /** All of one manga's chapters for one day, newest first. [items] is never empty. */
    data class Group<T>(val key: GroupKey, val items: List<T>) : GroupedRow<T> {
        val leader: T get() = items.first()
        val hidden: List<T> get() = items.subList(1, items.size)
    }
}

data class GroupKey(val date: LocalDate, val mangaId: Long)

/**
 * Groups [items], which must already be sorted newest first (as upstream's Updates list is).
 */
inline fun <T> groupUpdates(
    items: List<T>,
    date: (T) -> LocalDate,
    mangaId: (T) -> Long,
): List<GroupedRow<T>> {
    val rows = ArrayList<GroupedRow<T>>()
    var index = 0
    while (index < items.size) {
        val day = date(items[index])
        val dayEnd = run {
            var end = index
            while (end < items.size && date(items[end]) == day) end++
            end
        }
        rows += GroupedRow.Header(day, chapterCount = dayEnd - index)
        // LinkedHashMap keeps first-appearance order of each manga within the day
        val byManga = LinkedHashMap<Long, ArrayList<T>>()
        for (i in index until dayEnd) {
            byManga.getOrPut(mangaId(items[i])) { ArrayList() } += items[i]
        }
        byManga.forEach { (id, chapters) -> rows += GroupedRow.Group(GroupKey(day, id), chapters) }
        index = dayEnd
    }
    return rows
}
