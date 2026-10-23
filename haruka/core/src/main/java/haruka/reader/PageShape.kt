package haruka.reader

/** Pixel size of a page image, as read from its header. */
data class PageSize(val width: Int, val height: Int)

/** Tells a long strip from normal pages by the shape of a few pages of a chapter. */
object PageShape {

    /** Pages at least this many times taller than wide are long-strip slices. */
    const val LONG_STRIP_RATIO = 2.0

    private const val SAMPLE_SIZE = 3

    /**
     * Indices of the pages to measure in a chapter of [pageCount] pages: the second to fourth, as the first is often
     * a cover or credits page. A one-page chapter has only its first page to go on.
     */
    fun sampleIndices(pageCount: Int): IntRange = when {
        pageCount <= 0 -> IntRange.EMPTY
        pageCount == 1 -> 0..0
        else -> 1..minOf(SAMPLE_SIZE, pageCount - 1)
    }

    /**
     * Whether the median height/width of [pages] is at least [LONG_STRIP_RATIO]. The median keeps one odd page (a
     * spread, a credits slice) from deciding. Null when no page has a usable size.
     */
    fun isLongStrip(pages: List<PageSize>): Boolean? {
        val ratios = pages
            .filter { it.width > 0 && it.height > 0 }
            .map { it.height.toDouble() / it.width }
            .sorted()
        if (ratios.isEmpty()) return null
        val mid = ratios.size / 2
        val median = if (ratios.size % 2 == 1) ratios[mid] else (ratios[mid - 1] + ratios[mid]) / 2
        return median >= LONG_STRIP_RATIO
    }
}
