package haruka.updates

/** A chapter name split so the chapter itself reads first and the [extra] note can be shown quieter. */
internal data class ChapterLineParts(val chapter: String, val extra: String?)

/**
 * Splits a trailing parenthesised note, usually the scanlator ("Chapter 18.2 (Flaemes TL)"), off [chapterName], and
 * adds [scanlator] when the name doesn't already mention it.
 */
internal fun chapterLineParts(chapterName: String, scanlator: String?): ChapterLineParts {
    val trimmed = chapterName.trimEnd()
    val noteStart = trailingParenthesisStart(trimmed)
    val chapter = noteStart?.let { trimmed.substring(0, it).trimEnd() } ?: chapterName
    val note = noteStart?.let { trimmed.substring(it) }
    val group = scanlator?.trim()?.takeIf { it.isNotEmpty() && !chapterName.contains(it, ignoreCase = true) }
    val extra = listOfNotNull(note, group).joinToString(" · ").ifEmpty { null }
    return ChapterLineParts(chapter, extra)
}

/** Index of the "(" matching [text]'s final ")", or null when there is no such group after some chapter text. */
private fun trailingParenthesisStart(text: String): Int? {
    if (!text.endsWith(')')) return null
    var depth = 0
    for (i in text.indices.reversed()) {
        when (text[i]) {
            ')' -> depth++
            '(' -> if (--depth == 0) return i.takeIf { text.substring(0, it).isNotBlank() }
        }
    }
    return null
}
