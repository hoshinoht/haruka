package haruka.chapter.ui

import haruka.chapter.dedup.ChapterDeduper
import haruka.chapter.dedup.DedupChapter
import haruka.chapter.dedup.DedupOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** One logical chapter of the grouped chapter list. */
class ChapterGroup(
    /** Smallest chapter id of the logical chapter: unchanged when copies are re-ranked or newer ones arrive. */
    val key: Long,
    /** The winner copy's parts, first part first; the first is the row that stands for the group. */
    val winner: List<Long>,
    /** Every part of every other copy, best copy first. */
    val others: List<Long>,
    /** Number of copies, the winner included. */
    val versions: Int,
) {
    val head: Long get() = winner[0]

    /** Whether the group needs more than its head row: other copies or more parts. */
    val isGrouped: Boolean get() = versions > 1 || winner.size > 1
}

/** How a chapter row takes part in a group. Plain single-copy chapters have none. */
data class ChapterGroupRow(
    val key: Long,
    /** The row standing for the whole group; otherwise a part or copy revealed by expanding it. */
    val head: Boolean,
    val versions: Int,
    val parts: Int,
    val expanded: Boolean,
    val expansion: ChapterGroupExpansion,
)

/** Keys of the expanded groups. Held by the screen's ViewModel, so they survive scrolling and config changes. */
class ChapterGroupExpansion {
    private val keys = MutableStateFlow(emptySet<Long>())
    val expanded: StateFlow<Set<Long>> = keys.asStateFlow()

    fun toggle(key: Long) = keys.update { if (key in it) it - key else it + key }
}

/**
 * Groups an already filtered and sorted chapter list. Each group sits where its logical chapter first appears, so
 * the upstream sort holds.
 */
fun buildChapterGroups(
    chapters: List<DedupChapter>,
    deduper: ChapterDeduper,
    options: DedupOptions,
    isDownloaded: (DedupChapter) -> Boolean,
): List<ChapterGroup> = deduper.dedupe(chapters, options, isDownloaded).map { deduped ->
    val winner = deduped.winner.parts.map { it.id }
    var key = winner.min()
    val others = ArrayList<Long>()
    for (copy in deduped.others) {
        for (part in copy.parts) {
            others += part.id
            if (part.id < key) key = part.id
        }
    }
    ChapterGroup(key, winner, others, deduped.logical.copies.size)
}

/**
 * The visible rows: every group's head, followed, when the group is [expanded], by the winner's other parts and then
 * the other copies. [item] builds a row from a chapter id and its group role, null for a plain chapter.
 */
inline fun <T> List<ChapterGroup>.toRows(
    expanded: Set<Long>,
    expansion: ChapterGroupExpansion,
    item: (id: Long, row: ChapterGroupRow?) -> T,
): List<T> {
    val rows = ArrayList<T>(size)
    for (group in this) {
        if (!group.isGrouped) {
            rows += item(group.head, null)
            continue
        }
        val isExpanded = group.key in expanded
        val head = ChapterGroupRow(group.key, true, group.versions, group.winner.size, isExpanded, expansion)
        rows += item(group.head, head)
        if (!isExpanded) continue
        val child = head.copy(head = false)
        for (index in 1..<group.winner.size) rows += item(group.winner[index], child)
        for (id in group.others) rows += item(id, child)
    }
    return rows
}

/** A head row stands for its winner: (de)selecting the head (de)selects the winner's other parts along with it. */
fun syncWinnerSelection(before: Set<Long>, after: MutableSet<Long>, groups: List<ChapterGroup>) {
    for (group in groups) {
        if (group.winner.size < 2) continue
        val selected = group.head in after
        if (selected == (group.head in before)) continue
        for (index in 1..<group.winner.size) {
            if (selected) after += group.winner[index] else after -= group.winner[index]
        }
    }
}

/** [ids] plus, for each group head among them, every copy of its chapter: a head row marks the whole group. */
fun withGroupCopies(ids: Collection<Long>, groupsByHead: Map<Long, ChapterGroup>): Set<Long> {
    val result = LinkedHashSet<Long>(ids)
    for (id in ids) {
        val group = groupsByHead[id] ?: continue
        result += group.winner
        result += group.others
    }
    return result
}
