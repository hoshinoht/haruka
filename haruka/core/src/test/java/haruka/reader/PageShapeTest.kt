package haruka.reader

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PageShapeTest {

    private fun page(ratio: Double, width: Int = 800) = PageSize(width, (width * ratio).toInt())

    @Test
    fun `tall slices are a long strip, portrait pages are not`() {
        PageShape.isLongStrip(listOf(page(3.0), page(4.5), page(2.8))) shouldBe true
        PageShape.isLongStrip(listOf(page(1.42), page(1.5), page(1.38))) shouldBe false
    }

    @Test
    fun `a ratio of exactly two is a long strip`() {
        PageShape.isLongStrip(listOf(PageSize(700, 1400))) shouldBe true
        PageShape.isLongStrip(listOf(PageSize(700, 1399))) shouldBe false
        PageShape.isLongStrip(listOf(PageSize(700, 1400), PageSize(700, 1400), PageSize(700, 1400))) shouldBe true
    }

    @Test
    fun `the median outvotes one odd page`() {
        // A short credits slice among tall slices
        PageShape.isLongStrip(listOf(page(5.0), page(0.6), page(6.0))) shouldBe true
        // One tall page among normal ones
        PageShape.isLongStrip(listOf(page(1.4), page(12.0), page(1.45))) shouldBe false
    }

    @Test
    fun `fewer than three pages still decide`() {
        PageShape.isLongStrip(listOf(page(3.0))) shouldBe true
        PageShape.isLongStrip(listOf(page(1.4))) shouldBe false
        // Two pages: the mean of both
        PageShape.isLongStrip(listOf(page(1.0), page(3.0))) shouldBe true
        PageShape.isLongStrip(listOf(page(1.0), page(2.9))) shouldBe false
    }

    @Test
    fun `all-wide spreads stay paged`() {
        PageShape.isLongStrip(listOf(page(0.7), page(0.71), page(0.69))) shouldBe false
    }

    @Test
    fun `unusable sizes are ignored, and none at all decides nothing`() {
        PageShape.isLongStrip(emptyList()) shouldBe null
        PageShape.isLongStrip(listOf(PageSize(0, 0), PageSize(800, 0))) shouldBe null
        PageShape.isLongStrip(listOf(PageSize(0, 900), page(3.0))) shouldBe true
    }

    @Test
    fun `samples skip the first page and take at most three`() {
        PageShape.sampleIndices(0) shouldBe IntRange.EMPTY
        PageShape.sampleIndices(1) shouldBe 0..0
        PageShape.sampleIndices(2) shouldBe 1..1
        PageShape.sampleIndices(4) shouldBe 1..3
        PageShape.sampleIndices(60) shouldBe 1..3
    }
}
