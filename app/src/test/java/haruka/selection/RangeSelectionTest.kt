package haruka.selection

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class RangeSelectionTest {

    private val items = listOf("a", "b", "c", "d", "e", "f")

    @Test
    fun `long-press after a tap selects the inclusive range forwards`() {
        val selection = RangeSelection<String>().toggle("b").selectRange(items, "e")

        selection.selected shouldBe setOf("b", "c", "d", "e")
        selection.anchor shouldBe "e"
    }

    @Test
    fun `long-press selects the inclusive range backwards`() {
        val selection = RangeSelection<String>().toggle("e").selectRange(items, "b")

        selection.selected shouldBe setOf("b", "c", "d", "e")
    }

    @Test
    fun `ranges chain from the last long-pressed item`() {
        val selection = RangeSelection<String>()
            .toggle("a")
            .selectRange(items, "b")
            .selectRange(items, "d")

        selection.selected shouldBe setOf("a", "b", "c", "d")
    }

    @Test
    fun `long-press without a selection selects only that item and anchors it`() {
        val selection = RangeSelection<String>().selectRange(items, "c")

        selection.selected shouldBe setOf("c")
        selection.anchor shouldBe "c"
    }

    @Test
    fun `deselecting the anchor drops it so the next long-press does not range from it`() {
        val selection = RangeSelection<String>()
            .toggle("a")
            .toggle("f")
            .toggle("f")
            .selectRange(items, "c")

        selection.selected shouldBe setOf("a", "c")
    }

    @Test
    fun `anchor filtered out of the list behaves like no anchor`() {
        val selection = RangeSelection<String>().toggle("b").selectRange(listOf("c", "d", "e"), "e")

        selection.selected shouldBe setOf("b", "e")
        selection.anchor shouldBe "e"
    }

    @Test
    fun `long-press on an item not in the list changes nothing`() {
        val start = RangeSelection<String>().toggle("b")

        start.selectRange(items, "z") shouldBe start
    }

    @Test
    fun `range keeps earlier selections outside it`() {
        val selection = RangeSelection<String>()
            .toggle("a")
            .toggle("d")
            .selectRange(items, "f")

        selection.selected shouldBe setOf("a", "d", "e", "f")
    }
}
