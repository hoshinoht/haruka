package haruka.chapter.dedup.ui

import haruka.chapter.dedup.DedupRule
import haruka.chapter.dedup.ScanlatorNames

/** List edits behind the dedup settings screens; ranking names compare by [ScanlatorNames.normalize]. */
object DedupRanking {

    /** [list] with the item at [index] swapped one step towards the top; unchanged at the top or out of range. */
    fun <T> moveUp(list: List<T>, index: Int): List<T> = swap(list, index, index - 1)

    /** [list] with the item at [index] swapped one step towards the bottom; unchanged at the bottom or out of range. */
    fun <T> moveDown(list: List<T>, index: Int): List<T> = swap(list, index, index + 1)

    private fun <T> swap(list: List<T>, from: Int, to: Int): List<T> {
        if (from !in list.indices || to !in list.indices) return list
        return list.toMutableList().apply {
            this[from] = list[to]
            this[to] = list[from]
        }
    }

    fun isDefault(rules: List<DedupRule>): Boolean = rules == DedupRule.DEFAULT

    /**
     * Trims every name and keeps only the first of the names that normalize alike, so the ranking never holds two
     * spellings of one group. Names that normalize to nothing (punctuation only) are dropped: they could never match.
     */
    fun normalize(ranking: List<String>): List<String> {
        val seen = HashSet<String>(ranking.size)
        val result = ArrayList<String>(ranking.size)
        for (name in ranking) {
            val trimmed = name.trim()
            val key = ScanlatorNames.normalize(trimmed)
            if (key.isNotEmpty() && seen.add(key)) result += trimmed
        }
        return result
    }

    /** [ranking] with [name] appended at the bottom, unless it is blank or already ranked under another spelling. */
    fun add(ranking: List<String>, name: String): List<String> = normalize(ranking + name)

    /** The [names] not yet in [ranking] under any spelling, in their given order. */
    fun unranked(names: List<String>, ranking: List<String>): List<String> {
        val ranked = ranking.mapTo(HashSet(ranking.size), ScanlatorNames::normalize)
        return names.filterNot { ScanlatorNames.normalize(it) in ranked }
    }

    /**
     * The single groups in raw chapter [scanlators] strings ("A & B" yields A and B), one spelling per group,
     * sorted case-insensitively.
     */
    fun groups(scanlators: Iterable<String>): List<String> =
        normalize(scanlators.flatMap(ScanlatorNames::split)).sortedWith(String.CASE_INSENSITIVE_ORDER)

    /**
     * A manga's groups ([names], see [groups]) ordered by their position in [preferred]; groups missing from it
     * follow in their given order. Seeds the per-manga ranking from the global one or a saved override.
     */
    fun ordered(preferred: List<String>, names: List<String>): List<String> {
        val position = HashMap<String, Int>(preferred.size)
        preferred.forEachIndexed { index, name -> position.putIfAbsent(ScanlatorNames.normalize(name), index) }
        return names.sortedBy { position[ScanlatorNames.normalize(it)] ?: Int.MAX_VALUE }
    }
}
