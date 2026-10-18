package haruka.selection

/**
 * Selection with an explicit anchor, like Mihon's library range selection.
 *
 * Tap toggles one item and moves the anchor to it when it becomes selected. Long-press selects every item
 * between the anchor and the pressed item (inclusive, either direction) and moves the anchor there, so
 * ranges can be chained. If the anchor is gone (deselected, or filtered out of [ordered]), long-press just
 * selects the pressed item and makes it the new anchor.
 */
data class RangeSelection<K>(val selected: Set<K> = emptySet(), val anchor: K? = null) {

    fun toggle(key: K): RangeSelection<K> =
        if (key in selected) {
            RangeSelection(selected - key, anchor.takeUnless { it == key })
        } else {
            RangeSelection(selected + key, key)
        }

    fun selectRange(ordered: List<K>, key: K): RangeSelection<K> {
        val to = ordered.indexOf(key)
        if (to < 0) return this
        val from = anchor?.takeIf { it in selected }?.let(ordered::indexOf) ?: -1
        if (from < 0) return RangeSelection(selected + key, key)
        val range = ordered.subList(minOf(from, to), maxOf(from, to) + 1)
        return RangeSelection(selected + range, key)
    }

    /** Selects every item in [ordered]; the anchor is kept so a following long-press still ranges from it. */
    fun selectAll(ordered: List<K>): RangeSelection<K> = RangeSelection(selected + ordered, anchor)

    fun clear(): RangeSelection<K> = RangeSelection()
}
