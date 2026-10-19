package haruka.chapter.dedup

/**
 * Unread parts of the other copies of every logical chapter that [newlyRead] finishes, plus every copy's repeated
 * part uploads ([ChapterCopy.duplicates]). A copy is finished once all of its parts are read, so reading only the
 * first part of a split chapter marks nothing yet.
 */
fun duplicateCopiesToMarkRead(
    chapters: List<DedupChapter>,
    mergeSplitChapters: Boolean,
    newlyRead: Set<Long>,
    isRead: (Long) -> Boolean,
): Set<Long> {
    if (newlyRead.isEmpty()) return emptySet()
    val result = LinkedHashSet<Long>()
    for (logical in groupLogicalChapters(chapters, mergeSplitChapters)) {
        if (!logical.hasDuplicates()) continue
        val finished = logical.copies.any { copy ->
            copy.parts.any { it.id in newlyRead } && copy.parts.all { it.id in newlyRead || isRead(it.id) }
        }
        if (!finished) continue
        for (copy in logical.copies) {
            for (part in copy.parts) {
                if (part.id !in newlyRead && !isRead(part.id)) result += part.id
            }
            for (part in copy.duplicates) {
                if (part.id !in newlyRead && !isRead(part.id)) result += part.id
            }
        }
    }
    return result
}

/**
 * The [candidates] whose logical chapter already has another copy with every part [downloaded]. A repeated part
 * upload ([ChapterCopy.duplicates]) is dropped once any copy, its own included, is downloaded.
 */
fun chaptersWithDownloadedCopy(
    chapters: List<DedupChapter>,
    mergeSplitChapters: Boolean,
    candidates: Set<Long>,
    downloaded: Set<Long>,
): Set<Long> {
    if (candidates.isEmpty() || downloaded.isEmpty()) return emptySet()
    val result = HashSet<Long>()
    for (logical in groupLogicalChapters(chapters, mergeSplitChapters)) {
        if (!logical.hasDuplicates()) continue
        val downloadedCopies = logical.copies.filter { copy -> copy.parts.all { it.id in downloaded } }
        if (downloadedCopies.isEmpty()) continue
        for (copy in logical.copies) {
            for (part in copy.parts) {
                if (part.id in candidates && downloadedCopies.any { it !== copy }) result += part.id
            }
            for (part in copy.duplicates) {
                if (part.id in candidates) result += part.id
            }
        }
    }
    return result
}

/**
 * Chapter ids the reader steps through: each logical chapter's winner, except that the chapter being read keeps the
 * copy holding [currentId]. When [currentId] is a repeated part upload, it stands in for that part of its copy.
 */
fun readerChapterIds(deduped: List<DedupedChapter>, currentId: Long): Set<Long> {
    val result = HashSet<Long>()
    for (chapter in deduped) {
        val copy = chapter.others.firstOrNull { copy -> copy.holds(currentId) } ?: chapter.winner
        val current = copy.duplicates.firstOrNull { it.id == currentId }
        if (current == null) {
            copy.parts.mapTo(result) { it.id }
        } else {
            val part = partIndex(current)
            copy.parts.mapTo(result) { if (partIndex(it) == part) current.id else it.id }
        }
    }
    return result
}

/** Whether there is anything besides one copy's parts: another copy or a repeated part upload. */
private fun LogicalChapter.hasDuplicates(): Boolean = copies.size > 1 || copies[0].duplicates.isNotEmpty()

private fun ChapterCopy.holds(id: Long): Boolean = parts.any { it.id == id } || duplicates.any { it.id == id }
