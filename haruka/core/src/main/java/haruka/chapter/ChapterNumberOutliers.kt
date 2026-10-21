package haruka.chapter

import kotlin.math.floor

/**
 * Some sources put an id or a timestamp where the chapter number goes (Manga Ball: "Ch. 1482765166" in a series that
 * ends at 69), which makes Mihon report about 1.5 billion missing chapters. This finds such numbers so the missing
 * count and gap dividers can treat them as unknown. Stored data is never changed.
 *
 * Returns the smallest outlier number (every number at or above it is one), or null when there is none. A number
 * is an outlier when it is above [ABSOLUTE_LIMIT], or when the series' known numbers jump by more than [MIN_JUMP] to
 * more than [FACTOR] times the number before, and only a few numbers ([MAX_TAIL], or 5% of them) lie past the jump.
 * A series that really continues past a big jump (numbering by year, say) has many chapters there and is left alone.
 */
fun chapterNumberOutlierThreshold(numbers: Iterable<Double>): Double? {
    val known = numbers.filter { it >= 0.0 }.map { floor(it) }.distinct().sorted()
    if (known.isEmpty()) return null
    val absolute = known.firstOrNull { it > ABSOLUTE_LIMIT }
    val maxTail = maxOf(MAX_TAIL, known.size / 20)
    for (index in 1..known.lastIndex) {
        val before = known[index - 1]
        val number = known[index]
        if (number - before > MIN_JUMP && number > FACTOR * maxOf(before, 1.0) && known.size - index <= maxTail) {
            return if (absolute != null) minOf(absolute, number) else number
        }
    }
    return absolute
}

/** Whether [number] is at or above [threshold] (see [chapterNumberOutlierThreshold]). */
fun isChapterNumberOutlier(number: Double, threshold: Double?): Boolean = threshold != null && number >= threshold

private const val ABSOLUTE_LIMIT = 100_000.0
private const val MIN_JUMP = 1_000.0
private const val FACTOR = 10.0
private const val MAX_TAIL = 3
