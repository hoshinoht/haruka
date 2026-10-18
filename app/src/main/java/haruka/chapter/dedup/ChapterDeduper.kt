package haruka.chapter.dedup

/**
 * One rule of the winner comparison; the first rule that tells two copies apart decides. The lowest chapter id is
 * always the final tie-break so the winner never depends on input order.
 */
enum class DedupRule {
    /** Every part of the copy is downloaded. */
    DOWNLOADED,

    /** A group is a known official publisher, or the source itself is one. */
    OFFICIAL,

    /** Official or doubtfully official ("Official?"). */
    OFFICIAL_UNCERTAIN,

    /** A group is in the user's ranked list; earlier entries win, unranked groups lose. */
    USER_RANK,

    /** Most recent upload date. */
    NEWEST_UPLOAD,

    /** Lowest source order. */
    SOURCE_ORDER,
    ;

    companion object {
        val DEFAULT: List<DedupRule> = entries
    }
}

data class DedupOptions(
    val rules: List<DedupRule> = DedupRule.DEFAULT,
    /** Scanlator names, best first, compared after [ScanlatorNames.normalize]. */
    val rankedScanlators: List<String> = emptyList(),
    val mergeSplitChapters: Boolean = true,
    /** The manga's source is itself a publisher (see [OfficialCatalog.isOfficialSource]). */
    val sourceIsOfficial: Boolean = false,
)

/** The chosen [winner] of a [logical] chapter and the [others], best first. */
data class DedupedChapter(
    val logical: LogicalChapter,
    val winner: ChapterCopy,
    val others: List<ChapterCopy>,
)

class ChapterDeduper(private val catalog: OfficialCatalog) {

    /**
     * Groups one manga's [chapters] into logical chapters (input order kept) and picks one winner copy of each.
     * A copy counts as downloaded only when [isDownloaded] holds for all of its parts.
     */
    fun dedupe(
        chapters: List<DedupChapter>,
        options: DedupOptions = DedupOptions(),
        isDownloaded: (DedupChapter) -> Boolean = { false },
    ): List<DedupedChapter> {
        val ranks = HashMap<String, Int>()
        val tiers = HashMap<String?, ScanlatorTier>()
        val comparator = CandidateComparator(options.rules.toTypedArray())
        options.rankedScanlators.forEachIndexed { index, name ->
            val key = ScanlatorNames.normalize(name)
            if (key !in ranks) ranks[key] = index
        }

        return groupLogicalChapters(chapters, options.mergeSplitChapters).map { logical ->
            val candidates = logical.copies.map { copy ->
                val tier = tiers.getOrPut(copy.scanlator) { tierOf(copy.scanlator, ranks, options.sourceIsOfficial) }
                Candidate(copy, tier, isDownloaded)
            }
            val ordered = if (candidates.size == 1) candidates else candidates.sortedWith(comparator)
            DedupedChapter(logical, ordered.first().copy, ordered.subList(1, ordered.size).map { it.copy })
        }
    }

    private fun tierOf(scanlator: String?, ranks: Map<String, Int>, sourceIsOfficial: Boolean): ScanlatorTier {
        val status = if (sourceIsOfficial) OfficialStatus.OFFICIAL else catalog.statusOf(scanlator)
        var rank = Int.MAX_VALUE
        if (!scanlator.isNullOrBlank() && ranks.isNotEmpty()) {
            // The whole string is checked too, so a user can rank a joint release ("A & B") as one entry.
            ranks[ScanlatorNames.normalize(scanlator)]?.let { rank = it }
            for (part in ScanlatorNames.split(scanlator)) {
                ranks[ScanlatorNames.normalize(part)]?.let { if (it < rank) rank = it }
            }
        }
        return ScanlatorTier(status, rank)
    }
}

private class ScanlatorTier(val status: OfficialStatus, val rank: Int)

private class Candidate(val copy: ChapterCopy, tier: ScanlatorTier, isDownloaded: (DedupChapter) -> Boolean) {
    val downloaded = copy.parts.all(isDownloaded)
    val official = tier.status == OfficialStatus.OFFICIAL
    val maybeOfficial = tier.status != OfficialStatus.NONE
    val rank = tier.rank
    val newestUpload = copy.parts.maxOf { it.dateUpload }
    val sourceOrder = copy.parts.minOf { it.sourceOrder }
    val id = copy.parts.minOf { it.id }
}

/** Orders better candidates first. Reads precomputed fields only. */
private class CandidateComparator(private val rules: Array<DedupRule>) : Comparator<Candidate> {
    override fun compare(a: Candidate, b: Candidate): Int {
        for (rule in rules) {
            val result = when (rule) {
                DedupRule.DOWNLOADED -> b.downloaded.compareTo(a.downloaded)
                DedupRule.OFFICIAL -> b.official.compareTo(a.official)
                DedupRule.OFFICIAL_UNCERTAIN -> b.maybeOfficial.compareTo(a.maybeOfficial)
                DedupRule.USER_RANK -> a.rank.compareTo(b.rank)
                DedupRule.NEWEST_UPLOAD -> b.newestUpload.compareTo(a.newestUpload)
                DedupRule.SOURCE_ORDER -> a.sourceOrder.compareTo(b.sourceOrder)
            }
            if (result != 0) return result
        }
        return a.id.compareTo(b.id)
    }
}
