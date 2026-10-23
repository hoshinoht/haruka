package haruka.reader

/**
 * Whether a series' genres (or its source's name) say it's read as one vertical strip: webtoon, long strip, manhwa
 * or manhua. Words are matched whole and case-insensitively, so `Long Strip`, `long-strip`, `Webtoons` and
 * `Manhwa (Korean)` count while `Full Color`, `Comic Strip` or `Manga` don't.
 */
object LongStripTags {

    private val WORDS = arrayOf("webtoon", "webtoons", "longstrip", "manhwa", "manwha", "manhua")

    // Spellings split over two words, e.g. "Long Strip", "Web Toon"
    private val WORD_PAIRS = arrayOf("long" to "strip", "long" to "strips", "web" to "toon", "web" to "toons")

    fun matches(genres: List<String>?, sourceName: String?): Boolean =
        genres?.any(::isLongStripName) == true || sourceName?.let(::isLongStripName) == true

    // Walks the letter runs in place rather than splitting, so a long genre list allocates nothing.
    private fun isLongStripName(text: String): Boolean {
        var prevStart = -1
        var prevEnd = -1
        var i = 0
        while (i < text.length) {
            if (!text[i].isLetter()) {
                i++
                continue
            }
            val start = i
            while (i < text.length && text[i].isLetter()) i++
            if (WORDS.any { text.wordIs(start, i, it) }) return true
            val pairMatches = prevStart >= 0 &&
                WORD_PAIRS.any { (first, second) ->
                    text.wordIs(prevStart, prevEnd, first) && text.wordIs(start, i, second)
                }
            if (pairMatches) return true
            prevStart = start
            prevEnd = i
        }
        return false
    }

    private fun String.wordIs(start: Int, end: Int, word: String): Boolean =
        end - start == word.length && regionMatches(start, word, 0, word.length, ignoreCase = true)
}
