package haruka.reader.loading

/** Chunks at the front of a download plan that keep loading when a new plan replaces it. */
const val PLAN_LEADING_CHUNKS = 2

/** Upstream's largest chunk of pages downloading together in the current chapter. */
const val DEFAULT_PAGES_AT_ONCE = 5

/** Upstream's parallel downloads for a chapter's first pages, before the reader asks for any page. */
const val DEFAULT_PARALLEL_DOWNLOADS = 1

/**
 * Whether [index] is in the first [leadingChunks] chunks of the current download [plan] (page indices per chunk, in
 * download order). Those pages are already being fetched first, so asking for one of them doesn't need a new plan.
 * No plan covers nothing.
 */
fun isCoveredByPlan(plan: List<List<Int>>, index: Int, leadingChunks: Int = PLAN_LEADING_CHUNKS): Boolean =
    plan.asSequence().take(leadingChunks).any { index in it }

/**
 * Rolling chunk sizes (start, end) for the first pages of a chapter loaded in the background, from the "parallel page
 * downloads" setting. 1 is upstream's one page at a time; more starts with that many pages and grows by one.
 */
fun preloadChunkSizes(parallel: Int): Pair<Int, Int> {
    val start = parallel.coerceIn(1, 3)
    return if (start == 1) 1 to 1 else start to start + 1
}
