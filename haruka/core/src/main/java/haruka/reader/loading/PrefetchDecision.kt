package haruka.reader.loading

/** Pages after the current one that must be loaded before the next chapter starts preloading. */
const val PREFETCH_LOOKAHEAD = 6

/** The next chapter starts preloading once the current page is one of this many last pages of the chapter. */
const val PREFETCH_TAIL = 5

/**
 * Whether the next chapter may start preloading while the current online chapter is still loading.
 *
 * [done] holds one entry per page of the current chapter, true once the page is ready or has failed. The next chapter
 * starts once every page is done (upstream's rule), once the pages from [currentIndex] through
 * [currentIndex] + [lookahead] are done, or once [currentIndex] is one of the last [tailSize] pages. An index outside
 * the chapter is clamped to it.
 */
fun shouldPrefetchNextChapter(
    done: List<Boolean>,
    currentIndex: Int,
    lookahead: Int = PREFETCH_LOOKAHEAD,
    tailSize: Int = PREFETCH_TAIL,
): Boolean {
    if (done.all { it }) return true
    val current = currentIndex.coerceIn(0, done.lastIndex)
    if (done.lastIndex - current < tailSize) return true
    val end = (current + lookahead.coerceAtLeast(0)).coerceAtMost(done.lastIndex)
    return (current..end).all { done[it] }
}
