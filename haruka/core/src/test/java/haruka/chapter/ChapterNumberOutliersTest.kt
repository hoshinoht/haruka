package haruka.chapter

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ChapterNumberOutliersTest {

    private fun range(from: Int, to: Int) = (from..to).map { it.toDouble() }

    @Test
    fun `an id in place of the chapter number is an outlier`() {
        // Manga Ball: chapters 0 to 69, plus one numbered 1482765166 (stored as Float, so 1482765184)
        val threshold = chapterNumberOutlierThreshold(range(0, 69) + 1482765184.0)
        threshold shouldBe 1482765184.0
        isChapterNumberOutlier(69.0, threshold) shouldBe false
        isChapterNumberOutlier(1482765184.0, threshold) shouldBe true
    }

    @Test
    fun `a long series has none`() {
        chapterNumberOutlierThreshold(range(1, 1150)) shouldBe null
    }

    @Test
    fun `a series that continues past a jump keeps its numbers`() {
        chapterNumberOutlierThreshold(listOf(0.0) + range(2001, 2050)) shouldBe null
    }

    @Test
    fun `a jump below the minimum size is kept`() {
        chapterNumberOutlierThreshold(range(1, 20) + 900.0) shouldBe null
    }

    @Test
    fun `a few numbers past the jump are all outliers`() {
        chapterNumberOutlierThreshold(range(1, 40) + listOf(300000.0, 300001.0)) shouldBe 300000.0
    }

    @Test
    fun `a huge number is an outlier even on its own`() {
        chapterNumberOutlierThreshold(listOf(1482765184.0)) shouldBe 1482765184.0
        chapterNumberOutlierThreshold(listOf(-1.0, 5.0)) shouldBe null
    }

    @Test
    fun `split and noisy numbers count by their whole chapter`() {
        chapterNumberOutlierThreshold(listOf(1.0, 1.5, 2.0, 3.000003333333, 99.0, 100.0)) shouldBe null
    }
}
