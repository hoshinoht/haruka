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

/** One upload of a logical chapter: a single chapter, or every part of a split chapter from one scanlator. */
data class ChapterCopy(val parts: List<DedupChapter>) {
    val scanlator: String? get() = parts.first().scanlator
}

/**
 * Every copy of one chapter of a manga.
 *
 * [number] is the whole chapter number and [part] the part index (1.10 is number 1, part 10), or 0 for a whole
 * chapter, which includes merged split chapters. Unknown numbers (< 0) are never grouped: each is its own logical
 * chapter with a single copy. With split merging off, [number] is the raw chapter number and [part] is always 0.
 */
data class LogicalChapter(
    val number: Double,
    val part: Int,
    val copies: List<ChapterCopy>,
)

/**
 * Groups one manga's [chapters] into logical chapters, in order of first appearance.
 *
 * With [mergeSplitChapters], a scanlator's parts x.1, x.2, … collapse into one copy of chapter x when that scanlator
 * has at least the two consecutive parts x.1 and x.2, no repeated part index for x, and no whole chapter x. Only the
 * run starting at .1 merges; a lone x.5 (usually an extra) stays its own chapter.
 */
fun groupLogicalChapters(chapters: List<DedupChapter>, mergeSplitChapters: Boolean): List<LogicalChapter> {
    val parts = if (mergeSplitChapters) IntArray(chapters.size) { partIndex(chapters[it]) } else null
    val mergedCopies = if (parts != null) findMergedCopies(chapters, parts) else emptyMap()

    val groups = LinkedHashMap<Any, LogicalBuilder>()
    for ((index, chapter) in chapters.withIndex()) {
        val number = chapter.chapterNumber
        if (number < 0) {
            groups[UnknownKey(index)] = LogicalBuilder(number, 0).apply { copies += ChapterCopy(listOf(chapter)) }
            continue
        }
        val part = parts?.get(index) ?: 0
        val merged = mergedCopies[chapter.id]
        when {
            merged != null -> {
                val builder = groups.getOrPut(LogicalKey(merged.number, 0)) { LogicalBuilder(merged.number, 0) }
                // A merged copy is added once, when its first part shows up.
                if (builder.copies.none { it === merged.copy }) builder.copies += merged.copy
            }
            part > 0 -> {
                val whole = number.toLong().toDouble()
                groups.getOrPut(LogicalKey(whole, part)) { LogicalBuilder(whole, part) }.copies +=
                    ChapterCopy(listOf(chapter))
            }
            else -> groups.getOrPut(LogicalKey(number, 0)) { LogicalBuilder(number, 0) }.copies +=
                ChapterCopy(listOf(chapter))
        }
    }
    return groups.values.map { LogicalChapter(it.number, it.part, it.copies) }
}

private data class LogicalKey(val number: Double, val part: Int)

private data class UnknownKey(val index: Int)

private class LogicalBuilder(val number: Double, val part: Int) {
    val copies = ArrayList<ChapterCopy>(1)
}

private class MergedCopy(val number: Double, val copy: ChapterCopy)

private data class ScanlatorChapter(val scanlator: String?, val number: Long)

/** Maps each chapter id that belongs to a merged split chapter to its shared copy. */
private fun findMergedCopies(chapters: List<DedupChapter>, parts: IntArray): Map<Long, MergedCopy> {
    val candidates = HashMap<ScanlatorChapter, ArrayList<Int>>()
    val wholes = HashSet<ScanlatorChapter>()
    for ((index, chapter) in chapters.withIndex()) {
        val number = chapter.chapterNumber
        if (number < 0) continue
        val whole = number.toLong()
        val key = ScanlatorChapter(chapter.scanlator, whole)
        when {
            parts[index] > 0 -> candidates.getOrPut(key) { ArrayList() } += index
            number == whole.toDouble() -> wholes += key
        }
    }

    val merged = HashMap<Long, MergedCopy>()
    for ((key, indices) in candidates) {
        if (indices.size < 2 || key in wholes) continue
        indices.sortBy { parts[it] }
        if ((1 until indices.size).any { parts[indices[it]] == parts[indices[it - 1]] }) continue
        var runLength = 0
        while (runLength < indices.size && parts[indices[runLength]] == runLength + 1) runLength++
        if (runLength < 2) continue
        val copy = MergedCopy(key.number.toDouble(), ChapterCopy(List(runLength) { chapters[indices[it]] }))
        copy.copy.parts.forEach { merged[it.id] = copy }
    }
    return merged
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
