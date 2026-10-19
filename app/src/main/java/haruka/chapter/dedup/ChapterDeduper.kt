package haruka.chapter.dedup

/**
 * One rule of the winner comparison; the first rule that tells two copies apart decides. The lowest chapter id is
 * always the final tie-break so the winner never depends on input order.
 *
 * Before the rules, a low-quality copy (see [ScanlatorNames.isLowQuality]) loses to every other copy; only a leading
 * [DOWNLOADED] rule is applied ahead of that demotion.
 */
enum class DedupRule {
    /** Every part of the copy is downloaded. */
    DOWNLOADED,

    /**
     * A group is a known official publisher, or the source itself is one. On an aggregator source, a copy whose
     * every part is named "official translation" counts too.
     */
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
    /**
     * The manga's source mirrors many groups under pseudonyms (see [OfficialCatalog.isAggregatorSource]), so a chapter
     * named "official translation" there is trusted as official.
     */
    val sourceIsAggregator: Boolean = false,
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
        val comparator = CandidateComparator(options.rules)
        options.rankedScanlators.forEachIndexed { index, name ->
            val key = ScanlatorNames.normalize(name)
            if (key !in ranks) ranks[key] = index
        }
        val tierFor = { scanlator: String? ->
            tiers.getOrPut(scanlator) { tierOf(scanlator, ranks, options.sourceIsOfficial) }
        }
        val namesOfficial = options.sourceIsAggregator && !options.sourceIsOfficial

        return groupLogicalChapters(chapters, options.mergeSplitChapters).map { logical ->
            val candidates = logical.copies.map { copy ->
                Candidate(
                    copy = copy,
                    tier = tierFor(copy.scanlator),
                    namedOfficial = namesOfficial && copy.parts.all { isNamedOfficialTranslation(it.name) },
                    lowQuality = copy.parts.any { tierFor(it.scanlator).lowQuality },
                    isDownloaded = isDownloaded,
                )
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
        return ScanlatorTier(status, rank, scanlator != null && ScanlatorNames.isLowQuality(scanlator))
    }
}

// "un-official" is excluded along with "unofficial", hence the '-' in the lookbehind.
private val OFFICIAL_TRANSLATION = Regex(
    """(?<![\p{L}\p{N}-])official\s+translations?(?![\p{L}\p{N}])""",
    RegexOption.IGNORE_CASE,
)

/** Whether a chapter [name] claims an official translation ("Ch. 66 OFFICIAL TRANSLATION"). */
internal fun isNamedOfficialTranslation(name: String): Boolean = OFFICIAL_TRANSLATION.containsMatchIn(name)

private class ScanlatorTier(val status: OfficialStatus, val rank: Int, val lowQuality: Boolean)

private class Candidate(
    val copy: ChapterCopy,
    tier: ScanlatorTier,
    namedOfficial: Boolean,
    val lowQuality: Boolean,
    isDownloaded: (DedupChapter) -> Boolean,
) {
    val downloaded = copy.parts.all(isDownloaded)
    val official = namedOfficial || tier.status == OfficialStatus.OFFICIAL
    val maybeOfficial = official || tier.status != OfficialStatus.NONE
    val rank = tier.rank
    val newestUpload = copy.parts.maxOf { it.dateUpload }
    val sourceOrder = copy.parts.minOf { it.sourceOrder }
    val id = copy.parts.minOf { it.id }
    val split = copy.parts.size > 1
}

/** Orders better candidates first. Reads precomputed fields only. */
private class CandidateComparator(rules: List<DedupRule>) : Comparator<Candidate> {

    /**
     * The rules with the low-quality demotion (null) inserted first, or second behind a leading DOWNLOADED, and the
     * whole-over-split preference ([WHOLE_STEP]) right after the official rules: a whole upload of a chapter beats
     * the same chapter split into parts, unless the parts are more official.
     */
    private val steps: Array<Any?> = ArrayList<Any?>(rules.size + 2).apply {
        addAll(rules)
        val afterOfficial = maxOf(indexOf(DedupRule.OFFICIAL), indexOf(DedupRule.OFFICIAL_UNCERTAIN)) + 1
        add(afterOfficial, WHOLE_STEP)
        add(if (rules.firstOrNull() == DedupRule.DOWNLOADED) 1 else 0, null)
    }.toTypedArray()

    override fun compare(a: Candidate, b: Candidate): Int {
        for (step in steps) {
            val result = when (step) {
                null -> a.lowQuality.compareTo(b.lowQuality)
                WHOLE_STEP -> a.split.compareTo(b.split)
                DedupRule.DOWNLOADED -> b.downloaded.compareTo(a.downloaded)
                DedupRule.OFFICIAL -> b.official.compareTo(a.official)
                DedupRule.OFFICIAL_UNCERTAIN -> b.maybeOfficial.compareTo(a.maybeOfficial)
                DedupRule.USER_RANK -> a.rank.compareTo(b.rank)
                DedupRule.NEWEST_UPLOAD -> b.newestUpload.compareTo(a.newestUpload)
                DedupRule.SOURCE_ORDER -> a.sourceOrder.compareTo(b.sourceOrder)
                else -> error("Unknown dedup step $step")
            }
            if (result != 0) return result
        }
        return a.id.compareTo(b.id)
    }
}

private val WHOLE_STEP = Any()
