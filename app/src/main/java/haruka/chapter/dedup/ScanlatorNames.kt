package haruka.chapter.dedup

/**
 * The one scanlator normalizer used for official matching, user ranking and memo keys, so that spelling variants
 * seen in real libraries ("Lua Comic", "Lua_Comics", "Articuno (comick)", "MANGA Plus by SHUEISHA") meet.
 */
object ScanlatorNames {

    // Applied after lowercasing; needs a separator before "by" so names like "Ruby" survive.
    private val BY_SUFFIX = Regex("""[\s(\[]+by\s.*$""")

    /**
     * Casefold, drop a "by ..." suffix, drop a trailing parenthesised suffix (unless nothing would remain), keep only
     * letters and digits, then drop one trailing 's'. Matching on the result is always exact, never substring, so
     * "unofficial" never matches "official".
     */
    fun normalize(name: String): String {
        var text = BY_SUFFIX.replace(name.lowercase(), "")
        text = dropParenthesisedSuffix(text)
        val key = StringBuilder(text.length)
        for (c in text) {
            if (c.isLetterOrDigit()) key.append(c)
        }
        if (key.length > 1 && key[key.length - 1] == 's') key.setLength(key.length - 1)
        return key.toString()
    }

    /**
     * Splits a scanlator string holding several groups ("A & B", "A, B", "A + B", "A | B", "A and B") into its
     * trimmed, non-blank parts. Separators inside parentheses do not split, so a site suffix like "(x, y)" stays
     * attached to its group.
     */
    fun split(scanlator: String): List<String> {
        val parts = ArrayList<String>(2)
        var depth = 0
        var start = 0
        var i = 0
        while (i < scanlator.length) {
            val c = scanlator[i]
            var separatorLength = 0
            when (c) {
                '(', '[' -> depth++
                ')', ']' -> if (depth > 0) depth--
                '&', ',', '+', '|' -> if (depth == 0) separatorLength = 1
                ' ' -> if (depth == 0 && scanlator.regionMatches(i, " and ", 0, AND.length, ignoreCase = true)) {
                    separatorLength = AND.length
                }
            }
            if (separatorLength > 0) {
                addPart(parts, scanlator, start, i)
                i += separatorLength
                start = i
            } else {
                i++
            }
        }
        addPart(parts, scanlator, start, scanlator.length)
        return parts
    }

    private const val AND = " and "

    private fun addPart(parts: MutableList<String>, text: String, start: Int, end: Int) {
        val part = text.substring(start, end).trim()
        if (part.isNotEmpty()) parts += part
    }

    private fun dropParenthesisedSuffix(text: String): String {
        val trimmed = text.trimEnd()
        if (!trimmed.endsWith(')')) return trimmed
        val open = trimmed.lastIndexOf('(')
        if (open < 0) return trimmed
        val remainder = trimmed.substring(0, open)
        return if (remainder.isBlank()) trimmed else remainder
    }
}
