package haruka.title

// Left behind when a separator sat between the primary title and the dropped group, e.g. `Title - (a/ b)`.
private const val LEFTOVER_PUNCTUATION = ",;:-–—/|·、，："

/**
 * Drops a trailing alternative-name group such as Manga Ball's `Primary (alt1/ alt2/ ...)`, which can run to
 * well over a thousand characters. The group is the first ` (` whose contents up to its closing parenthesis (or
 * the end of the title, as sources cut long lists off) hold a `/ ` separator at the group's own level and that
 * nothing but whitespace follows. Anything else, like `Re:Zero (Light Novel)`, is returned unchanged.
 */
fun compactTitle(title: String): String {
    var open = title.indexOf('(')
    while (open >= 0) {
        if (open > 0 && title[open - 1].isWhitespace() && isTrailingAltGroup(title, open)) {
            val primary = title.substring(0, open).trimEnd { it.isWhitespace() || it in LEFTOVER_PUNCTUATION }
            return primary.ifEmpty { title }
        }
        open = title.indexOf('(', open + 1)
    }
    return title
}

private fun isTrailingAltGroup(title: String, open: Int): Boolean {
    var depth = 0
    var separated = false
    for (i in open..title.lastIndex) {
        when (title[i]) {
            '(' -> depth++
            ')' -> if (--depth == 0) {
                return separated && (i + 1..title.lastIndex).all { title[it].isWhitespace() }
            }
            // A bare slash, as in `(1/2)` or `(AC/DC)`, is part of a name rather than a list separator.
            '/' -> if (depth == 1 && (i == title.lastIndex || title[i + 1].isWhitespace())) separated = true
        }
    }
    return separated
}
