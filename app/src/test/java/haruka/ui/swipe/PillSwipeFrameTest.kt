package haruka.ui.swipe

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PillSwipeFrameTest {

    private fun frame(offset: Float) = pillSwipeFrame(
        offset = offset,
        threshold = 56f,
        pillMargin = 8f,
        maxRowCorner = 20f,
    )

    @Test
    fun `resting row has no pill, square corners and is not armed`() {
        frame(0f) shouldBe PillSwipeFrame(progress = 0f, pillWidth = 0f, rowCorner = 0f, armed = false)
    }

    @Test
    fun `pill stays hidden until the gap is wider than both margins`() {
        frame(10f).pillWidth shouldBe 0f
        frame(16f).pillWidth shouldBe 0f
        frame(20f).pillWidth shouldBe 4f
    }

    @Test
    fun `pill fills the uncovered gap minus its margins and keeps growing past the threshold`() {
        frame(56f).pillWidth shouldBe 40f
        frame(200f).pillWidth shouldBe 184f
    }

    @Test
    fun `row corner grows with progress and caps at the threshold`() {
        frame(28f).rowCorner shouldBe 10f
        frame(56f).rowCorner shouldBe 20f
        frame(300f).rowCorner shouldBe 20f
        frame(300f).progress shouldBe 1f
    }

    @Test
    fun `armed only strictly past the threshold, like the swipe library`() {
        frame(55.9f).armed shouldBe false
        frame(56f).armed shouldBe false
        frame(56.1f).armed shouldBe true
    }

    @Test
    fun `swiping towards the start mirrors swiping towards the end`() {
        frame(-120f) shouldBe frame(120f)
    }

    @Test
    fun `zero threshold arms on any movement`() {
        val zero = pillSwipeFrame(offset = 1f, threshold = 0f, pillMargin = 0f, maxRowCorner = 20f)
        zero.armed shouldBe true
        zero.rowCorner shouldBe 20f
    }
}
