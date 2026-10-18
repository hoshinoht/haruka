package haruka.chapter.dedup

import tachiyomi.domain.chapter.model.Chapter

/** The chapter fields dedup needs. [dateUpload] is epoch millis, 0 when unknown; a blank [scanlator] is null. */
data class DedupChapter(
    val id: Long,
    val name: String,
    val chapterNumber: Double,
    val scanlator: String?,
    val dateUpload: Long,
    val sourceOrder: Long,
)

fun Chapter.toDedupChapter(): DedupChapter = DedupChapter(
    id = id,
    name = name,
    chapterNumber = chapterNumber.withoutFloatNoise(),
    scanlator = scanlator?.ifBlank { null },
    dateUpload = dateUpload?.toEpochMilliseconds() ?: 0L,
    sourceOrder = sourceOrder,
)

/**
 * Backups store chapter numbers as Float and restore widens them, so a restored 6.1 reads 6.099999904632568 until the
 * next library update rewrites it. Such a value is recovered through the float's shortest decimal so split parts and
 * copies still meet; any other value is returned unchanged.
 */
internal fun Double.withoutFloatNoise(): Double {
    val float = toFloat()
    return if (float.toDouble() == this) float.toString().toDouble() else this
}

/**
 * One upload of a logical chapter: a single chapter, or every part of a split chapter from one scanlator.
 *
 * [duplicates] are the same group's repeated uploads of a part already in [parts] (only the newest upload of each
 * part is kept in [parts]). They are not read as part of the copy but still belong to it, so no chapter is lost.
 */
data class ChapterCopy(
    val parts: List<DedupChapter>,
    val duplicates: List<DedupChapter> = emptyList(),
) {
    val scanlator: String? get() = parts.first().scanlator
}

/**
 * What a chapter name says it is, from its first kind keyword. A name without one is [MAIN]. Copies of one number
 * with different kinds are different chapters (a "Spin-Off #3" is not a copy of "Episode 3").
 */
enum class ChapterKind {
    MAIN,
    SPIN_OFF,
    SIDE_STORY,
    EXTRA,
    EPILOGUE,
    PROLOGUE,
    OMAKE,
    SPECIAL,
    BONUS,
    AFTERWORD,
    ILLUSTRATION,
    ANNOUNCEMENT,
}

/**
 * Every copy of one chapter of a manga.
 *
 * [number] is the whole chapter number and [part] the part index (1.10 is number 1, part 10), or 0 for a whole
 * chapter, which includes merged split chapters. Unknown numbers (< 0) are never grouped: each is its own logical
 * chapter with a single copy. With split merging off, [number] is the raw chapter number and [part] is always 0.
 * [kind] separates differently named chapters sharing a number.
 */
data class LogicalChapter(
    val number: Double,
    val part: Int,
    val copies: List<ChapterCopy>,
    val kind: ChapterKind = ChapterKind.MAIN,
)

/**
 * Groups one manga's [chapters] into logical chapters, in order of first appearance. Chapters sharing a number but
 * not a [ChapterKind] form separate logical chapters.
 *
 * With [mergeSplitChapters], a scanlator's parts x.1, x.2, … of one kind collapse into one copy of chapter x when that
 * scanlator (compared after [ScanlatorNames.normalize]) has at least the two consecutive parts x.1 and x.2. A part
 * uploaded more than once keeps its newest upload; the others become the copy's [ChapterCopy.duplicates]. When the
 * scanlator also has the whole chapter x, the merged parts are one more copy of x competing with it. Only the run
 * starting at .1 merges; a lone x.5 (usually an extra) stays its own chapter.
 */
fun groupLogicalChapters(chapters: List<DedupChapter>, mergeSplitChapters: Boolean): List<LogicalChapter> {
    val kinds = Array(chapters.size) { chapterKind(chapters[it].name) }
    val parts = if (mergeSplitChapters) IntArray(chapters.size) { partIndex(chapters[it]) } else null
    val mergedCopies = if (parts != null) findMergedCopies(chapters, parts, kinds) else emptyMap()

    val groups = LinkedHashMap<Any, LogicalBuilder>()
    for ((index, chapter) in chapters.withIndex()) {
        val number = chapter.chapterNumber
        if (number < 0) {
            groups[UnknownKey(index)] =
                LogicalBuilder(number, 0, ChapterKind.MAIN).apply { copies += ChapterCopy(listOf(chapter)) }
            continue
        }
        val kind = kinds[index]
        val part = parts?.get(index) ?: 0
        val merged = mergedCopies[chapter.id]
        when {
            merged != null -> {
                val builder = groups.getOrPut(LogicalKey(merged.number, 0, kind)) {
                    LogicalBuilder(merged.number, 0, kind)
                }
                // A merged copy is added once, when its first part or duplicate shows up.
                if (builder.copies.none { it === merged.copy }) builder.copies += merged.copy
            }
            part > 0 -> {
                val whole = number.toLong().toDouble()
                groups.getOrPut(LogicalKey(whole, part, kind)) { LogicalBuilder(whole, part, kind) }.copies +=
                    ChapterCopy(listOf(chapter))
            }
            else -> groups.getOrPut(LogicalKey(number, 0, kind)) { LogicalBuilder(number, 0, kind) }.copies +=
                ChapterCopy(listOf(chapter))
        }
    }
    return groups.values.map { LogicalChapter(it.number, it.part, it.copies, it.kind) }
}

private data class LogicalKey(val number: Double, val part: Int, val kind: ChapterKind)

private data class UnknownKey(val index: Int)

private class LogicalBuilder(val number: Double, val part: Int, val kind: ChapterKind) {
    val copies = ArrayList<ChapterCopy>(1)
}

private class MergedCopy(val number: Double, val copy: ChapterCopy)

private data class SplitKey(val scanlator: String, val number: Long, val kind: ChapterKind)

/** Maps each chapter id that belongs to a merged split chapter, as a part or a duplicate, to its shared copy. */
private fun findMergedCopies(
    chapters: List<DedupChapter>,
    parts: IntArray,
    kinds: Array<ChapterKind>,
): Map<Long, MergedCopy> {
    val names = HashMap<String?, String>()
    val candidates = HashMap<SplitKey, ArrayList<Int>>()
    for ((index, chapter) in chapters.withIndex()) {
        if (parts[index] == 0) continue
        val scanlator = names.getOrPut(chapter.scanlator) { chapter.scanlator?.let(ScanlatorNames::normalize) ?: "" }
        candidates.getOrPut(SplitKey(scanlator, chapter.chapterNumber.toLong(), kinds[index])) { ArrayList() } += index
    }

    // Part order, then the upload kept for a repeated part first: newest upload, lowest source order, lowest id.
    val byPartThenNewest = Comparator<Int> { a, b ->
        val first = chapters[a]
        val second = chapters[b]
        when {
            parts[a] != parts[b] -> parts[a].compareTo(parts[b])
            first.dateUpload != second.dateUpload -> second.dateUpload.compareTo(first.dateUpload)
            first.sourceOrder != second.sourceOrder -> first.sourceOrder.compareTo(second.sourceOrder)
            else -> first.id.compareTo(second.id)
        }
    }
    val merged = HashMap<Long, MergedCopy>()
    for ((key, indices) in candidates) {
        if (indices.size < 2) continue
        indices.sortWith(byPartThenNewest)
        val run = ArrayList<DedupChapter>(indices.size)
        val duplicates = ArrayList<DedupChapter>(0)
        var i = 0
        while (i < indices.size && parts[indices[i]] == run.size + 1) {
            val part = parts[indices[i]]
            run += chapters[indices[i++]]
            while (i < indices.size && parts[indices[i]] == part) duplicates += chapters[indices[i++]]
        }
        if (run.size < 2) continue
        val copy = MergedCopy(key.number.toDouble(), ChapterCopy(run, duplicates))
        run.forEach { merged[it.id] = copy }
        duplicates.forEach { merged[it.id] = copy }
    }
    return merged
}

// Group n (1-based) matches ChapterKind.entries[n]; MAIN has no keyword.
private val CHAPTER_KIND = Regex(
    """(?<![\p{L}\p{N}])(?:(spin[\s_-]?offs?)|(side[\s_-]?stor(?:y|ies)|ss)|(extras?)|(epilogues?)|(prologues?)|""" +
        """(omake)|(specials?)|(bonus(?:es)?)|(afterwords?)|(illustrations?|illust)|""" +
        """(announcements?|notices?|hiatus))(?![\p{L}\p{N}])""",
    RegexOption.IGNORE_CASE,
)

/** The [ChapterKind] named by the first kind keyword in [name], or [ChapterKind.MAIN]. */
internal fun chapterKind(name: String): ChapterKind {
    val match = CHAPTER_KIND.find(name) ?: return ChapterKind.MAIN
    for (group in 1..<ChapterKind.entries.size) {
        if (match.groups[group] != null) return ChapterKind.entries[group]
    }
    return ChapterKind.MAIN
}

private val CHAPTER_PART = Regex(
    """\b(?:chapter|chap|ch)\.?\s*(\d+)\s*[-:,(]?\s*(?:part|pt)\.?\s*(\d+)""",
    RegexOption.IGNORE_CASE,
)
private val CHAPTER_DECIMAL = Regex("""\b(?:chapter|chap|ch)\.?\s*(\d+)\.(\d+)""", RegexOption.IGNORE_CASE)
private val PART_ONLY = Regex("""\b(?:part|pt)\.?\s*(\d+)""", RegexOption.IGNORE_CASE)

/**
 * Part index of [chapter] (12.3 or "Ch. 12 Part 3" is part 3), or 0 for a whole chapter or an unknown number.
 *
 * The chapter number is a Double, so "Ch. 1.10" arrives as 1.1; the name is read first to recover the real index,
 * and only trusted when it agrees with the number. Fractions written with a leading zero (1.05) are not parts.
 */
internal fun partIndex(chapter: DedupChapter): Int {
    val number = chapter.chapterNumber
    if (number < 0) return 0
    val whole = number.toLong()
    val isWhole = number == whole.toDouble()
    val name = chapter.name

    CHAPTER_PART.find(name)?.let { match ->
        val digits = match.groupValues[2]
        if (match.groupValues[1].toLongOrNull() == whole && (isWhole || decimal(whole, digits) == number)) {
            parsePart(digits).let { if (it > 0) return it }
        }
    }
    if (isWhole) return 0

    CHAPTER_DECIMAL.find(name)?.let { match ->
        val digits = match.groupValues[2]
        if (match.groupValues[1].toLongOrNull() == whole && decimal(whole, digits) == number) return parsePart(digits)
    }
    PART_ONLY.find(name)?.let { match ->
        val digits = match.groupValues[1]
        if (decimal(whole, digits) == number) return parsePart(digits)
    }
    val text = number.toString()
    if ('E' in text) return 0
    return parsePart(text.substringAfter('.'))
}

private fun parsePart(digits: String): Int = if (digits.startsWith('0')) 0 else digits.toIntOrNull() ?: 0

private fun decimal(whole: Long, digits: String): Double? = "$whole.$digits".toDoubleOrNull()
