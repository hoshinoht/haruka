package haruka.migration

import java.text.Normalizer

/** How far a bulk-migration match can be trusted without a look, and why. */
data class MatchConfidence(val level: Level, val reasons: List<MatchReason>) {
    enum class Level { HIGH, CHECK, LOW }
}

/**
 * One signal behind a [MatchConfidence]. Problems come first, with the one that made it [MatchConfidence.Level.LOW]
 * leading, followed by the signals that agree.
 */
sealed interface MatchReason {
    data class TitleDiffers(val similarity: Double) : MatchReason
    data class ProgressShort(val read: Double, val available: Double) : MatchReason
    data object AuthorMismatch : MatchReason
    data object TitleMatches : MatchReason
    data class ProgressCovered(val read: Double, val available: Double) : MatchReason
    data object AuthorMatches : MatchReason
}

/** What the scoring needs about an original entry and its migration candidate. */
data class MatchInput(
    val originalTitle: String,
    val candidateTitle: String,
    val originalAuthor: String?,
    val candidateAuthor: String?,
    /** Highest chapter number read on the original, or null if nothing is read. */
    val furthestReadChapter: Double?,
    /** Highest chapter number the candidate has. */
    val candidateLatestChapter: Double?,
)

enum class AuthorMatch { MATCH, MISMATCH, UNKNOWN }

private const val HIGH_SIMILARITY = 0.9
private const val LOW_SIMILARITY = 0.6

// A candidate at most this many chapters short of the furthest read chapter is still only worth a check.
private const val MAX_CHAPTER_SHORTFALL = 10.0

fun scoreMatch(input: MatchInput): MatchConfidence {
    val similarity = titleSimilarity(input.originalTitle, input.candidateTitle)
    val author = compareAuthors(input.originalAuthor, input.candidateAuthor)
    // Negative chapter numbers are Mihon's "unrecognised".
    val read = input.furthestReadChapter?.takeIf { it >= 0 }
    val available = input.candidateLatestChapter?.takeIf { it >= 0 }
    val progress = if (read != null && available != null) {
        if (read >
            available
        ) {
            MatchReason.ProgressShort(read, available)
        } else {
            MatchReason.ProgressCovered(read, available)
        }
    } else {
        null
    }

    val titleLow = similarity < LOW_SIMILARITY
    val progressLow =
        progress is MatchReason.ProgressShort && progress.read - progress.available > MAX_CHAPTER_SHORTFALL
    val titleProblem = MatchReason.TitleDiffers(similarity).takeIf { similarity < HIGH_SIMILARITY }
    val progressProblem = progress as? MatchReason.ProgressShort
    val authorProblem = MatchReason.AuthorMismatch.takeIf { author == AuthorMatch.MISMATCH }

    val problems = if (progressLow && !titleLow) {
        listOfNotNull(progressProblem, titleProblem, authorProblem)
    } else {
        listOfNotNull(titleProblem, progressProblem, authorProblem)
    }
    val agreements = listOfNotNull(
        progress as? MatchReason.ProgressCovered,
        MatchReason.TitleMatches.takeIf { titleProblem == null },
        MatchReason.AuthorMatches.takeIf { author == AuthorMatch.MATCH },
    )
    val level = when {
        titleLow || progressLow -> MatchConfidence.Level.LOW
        problems.isEmpty() -> MatchConfidence.Level.HIGH
        else -> MatchConfidence.Level.CHECK
    }
    return MatchConfidence(level, problems + agreements)
}

/**
 * Best normalized Levenshtein similarity of the raw titles and of their deep-search cleaned forms, so bracketed
 * tags, punctuation and case don't count as differences.
 */
fun titleSimilarity(original: String, candidate: String): Double {
    val raw = similarity(original, candidate)
    val cleanedOriginal = cleanTitle(original)
    val cleanedCandidate = cleanTitle(candidate)
    // Titles that clean down to nothing are not equal because of it.
    if (cleanedOriginal.isEmpty() || cleanedCandidate.isEmpty()) return raw
    return maxOf(raw, similarity(cleanedOriginal, cleanedCandidate))
}

/**
 * Deep search's title cleaning (lowercase, drop bracketed groups, Russian chapter references and punctuation,
 * collapse spaces) with two fixes: a title made only of bracketed text ("[Oshi no Ko]") keeps that text, and the
 * "too short, keep non-Latin letters" check measures the remaining text rather than the spaces left behind, so
 * CJK titles are not cleaned away.
 */
internal fun cleanTitle(title: String): String {
    val lower = title.lowercase()
    var cleaned = removeTextInBrackets(lower, readForward = true)
    if (cleaned.length <= 5) cleaned = removeTextInBrackets(lower, readForward = false)
    if (cleaned.isBlank()) cleaned = lower.filterNot { it in BRACKETS }
    cleaned = cleaned.replace(chapterRefCyrillicRegex, " ").trim()
    val latin = cleaned.replace(nonLatinRegex, " ").replace(spacesRegex, " ").trim()
    cleaned = if (latin.length <= 5) cleaned.replace(nonLetterRegex, " ") else latin
    return cleaned.trim().replace(" - ", " ").replace(spacesRegex, " ").trim()
}

/** Token-set comparison of author credits; blank credits or credits in different scripts are unknown. */
fun compareAuthors(original: String?, candidate: String?): AuthorMatch {
    val a = authorTokens(original)
    val b = authorTokens(candidate)
    if (a.isEmpty() || b.isEmpty()) return AuthorMatch.UNKNOWN
    // A romanised credit against one in the original script says nothing either way.
    if (a.any(::isLatin) != b.any(::isLatin)) return AuthorMatch.UNKNOWN
    if (a.any { it in b }) return AuthorMatch.MATCH
    // The same name written with and without a space ("尾田栄一郎" / "尾田 栄一郎").
    if (a.joinToString("") == b.joinToString("")) return AuthorMatch.MATCH
    return AuthorMatch.MISMATCH
}

private fun authorTokens(author: String?): List<String> {
    if (author.isNullOrBlank()) return emptyList()
    val decomposed = Normalizer.normalize(removeTextInBrackets(author, readForward = true), Normalizer.Form.NFD)
    return decomposed.replace(combiningMarksRegex, "")
        .lowercase()
        .split(authorSeparatorRegex)
        .filter { it.length >= 2 }
        .distinct()
}

private fun isLatin(token: String) = token.any { it in 'a'..'z' }

/** Normalized Levenshtein similarity over code points: 1 - distance / longer length. */
private fun similarity(a: String, b: String): Double {
    val x = a.codePoints().toArray()
    val y = b.codePoints().toArray()
    val longest = maxOf(x.size, y.size)
    if (longest == 0) return 1.0
    var previous = IntArray(y.size + 1) { it }
    var current = IntArray(y.size + 1)
    for (i in x.indices) {
        current[0] = i + 1
        for (j in y.indices) {
            val cost = if (x[i] == y[j]) 0 else 1
            current[j + 1] = minOf(current[j] + 1, previous[j + 1] + 1, previous[j] + cost)
        }
        val swap = previous
        previous = current
        current = swap
    }
    // Divide the matching length rather than subtract a fraction, so 9 of 10 is exactly 0.9.
    return (longest - previous[y.size]).toDouble() / longest
}

private fun removeTextInBrackets(text: String, readForward: Boolean): String {
    val openingChars = if (readForward) "([<{" else ")]}>"
    val closingChars = if (readForward) ")]}>" else "([<{"
    var depth = 0
    val kept = StringBuilder(text.length)
    for (index in text.indices) {
        val char = if (readForward) text[index] else text[text.length - 1 - index]
        when (char) {
            in openingChars -> depth++
            in closingChars -> if (depth > 0) depth--
            else -> if (depth == 0) kept.append(char)
        }
    }
    return if (readForward) kept.toString() else kept.reverse().toString()
}

private const val BRACKETS = "()[]<>{}"
private val nonLatinRegex = Regex("[^a-zA-Z0-9- ]")
private val nonLetterRegex = Regex("[^\\p{L}0-9- ]")
private val spacesRegex = Regex(" +")
private val chapterRefCyrillicRegex = Regex("""((- часть|- глава) \d*)""")
private val combiningMarksRegex = Regex("\\p{M}+")
private val authorSeparatorRegex = Regex("[^\\p{L}\\p{N}]+")
