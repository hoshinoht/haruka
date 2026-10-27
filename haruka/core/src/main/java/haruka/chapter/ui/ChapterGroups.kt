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
    /** The winner copy's parts, first part first. */
    val winner: List<Long>,
    /** The winner's repeated part uploads, then every part and repeated upload of every other copy, best copy first. */
    val others: List<Long>,
    /** Number of copies, the winner included. */
    val versions: Int,
    /** The winner part whose row stands for the group; see [headedAtFirstUnread]. */
    val head: Long = winner[0],
    /**
     * Whether a row the head stands for is downloaded: another copy, or some but not all of the winner's parts. The
     * head's own download indicator can't show it, so the group chip marks it.
     */
    val hiddenDownloaded: Boolean = false,
) {
    /** Whether the group needs more than its head row: other copies or more parts. */
    val isGrouped: Boolean get() = versions > 1 || winner.size > 1

    /** The winner's parts other than [head], in part order. */
    inline fun forEachOtherPart(action: (Long) -> Unit) {
        for (id in winner) if (id != head) action(id)
    }
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
    /** Head rows only: see [ChapterGroup.hiddenDownloaded]. */
    val hiddenDownloaded: Boolean = false,
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
    var hiddenDownloaded = false
    fun hide(parts: List<DedupChapter>) {
        for (part in parts) {
            others += part.id
            if (part.id < key) key = part.id
            if (isDownloaded(part)) hiddenDownloaded = true
        }
    }
    hide(deduped.winner.duplicates)
    for (copy in deduped.others) {
        hide(copy.parts)
        hide(copy.duplicates)
    }
    // A split winner with only some parts downloaded: the head may show the part that isn't
    if (!hiddenDownloaded && winner.size > 1) {
        val downloadedParts = deduped.winner.parts.count(isDownloaded)
        hiddenDownloaded = downloadedParts in 1 until winner.size
    }
    ChapterGroup(key, winner, others, deduped.logical.copies.size, hiddenDownloaded = hiddenDownloaded)
}

/**
 * Groups whose split winner is headed by its first unread part, so the row standing for the chapter is where reading
 * resumes (parts 1 and 2 read: part 3); with every part read, part 1. Read state is applied after [buildChapterGroups]
 * because marking a part read does not change the grouping.
 */
inline fun List<ChapterGroup>.headedAtFirstUnread(isRead: (Long) -> Boolean): List<ChapterGroup> = map { group ->
    if (group.winner.size < 2) return@map group
    val head = group.winner.firstOrNull { !isRead(it) } ?: group.winner[0]
    if (head == group.head) {
        group
    } else {
        ChapterGroup(group.key, group.winner, group.others, group.versions, head, group.hiddenDownloaded)
    }
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
        val head = ChapterGroupRow(
            group.key,
            true,
            group.versions,
            group.winner.size,
            isExpanded,
            expansion,
            group.hiddenDownloaded,
        )
        rows += item(group.head, head)
        if (!isExpanded) continue
        val child = head.copy(head = false, hiddenDownloaded = false)
        group.forEachOtherPart { rows += item(it, child) }
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
        group.forEachOtherPart { if (selected) after += it else after -= it }
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
