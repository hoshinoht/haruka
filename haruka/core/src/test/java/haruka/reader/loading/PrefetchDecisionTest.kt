package haruka.reader.loading

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PrefetchDecisionTest {

    private fun pages(size: Int, doneUntil: Int) = List(size) { it < doneUntil }

    @Test
    fun `an empty chapter has nothing to wait for`() {
        shouldPrefetchNextChapter(emptyList(), currentIndex = 0) shouldBe true
        shouldPrefetchNextChapter(emptyList(), currentIndex = -1) shouldBe true
    }

    @Test
    fun `a fully loaded chapter always prefetches, as upstream does`() {
        shouldPrefetchNextChapter(List(30) { true }, currentIndex = 0) shouldBe true
    }

    @Test
    fun `a chapter where every page failed counts as done`() {
        // Errors count as done, the same as upstream's wait
        shouldPrefetchNextChapter(List(30) { true }, currentIndex = 12) shouldBe true
    }

    @Test
    fun `waits while the pages just ahead are still loading`() {
        shouldPrefetchNextChapter(pages(30, doneUntil = 3), currentIndex = 0) shouldBe false
    }

    @Test
    fun `starts once the current page and the lookahead after it are done`() {
        // Pages 0..6 done: current 0 plus 6 ahead
        shouldPrefetchNextChapter(pages(30, doneUntil = 7), currentIndex = 0) shouldBe true
        // One page short of the lookahead
        shouldPrefetchNextChapter(pages(30, doneUntil = 6), currentIndex = 0) shouldBe false
    }

    @Test
    fun `pages behind the current one do not hold it back`() {
        val done = List(30) { it in 10..16 }
        shouldPrefetchNextChapter(done, currentIndex = 10) shouldBe true
    }

    @Test
    fun `a not yet loaded current page holds it back`() {
        val done = List(30) { it in 11..20 }
        shouldPrefetchNextChapter(done, currentIndex = 10) shouldBe false
    }

    @Test
    fun `starts near the end of the chapter even with pages still loading`() {
        val none = List(30) { false }
        shouldPrefetchNextChapter(none, currentIndex = 25) shouldBe true
        shouldPrefetchNextChapter(none, currentIndex = 29) shouldBe true
        shouldPrefetchNextChapter(none, currentIndex = 24) shouldBe false
    }

    @Test
    fun `a lookahead longer than the chapter only needs the rest of it`() {
        val done = List(10) { it >= 2 }
        shouldPrefetchNextChapter(done, currentIndex = 2, lookahead = 50, tailSize = 0) shouldBe true
        shouldPrefetchNextChapter(done, currentIndex = 1, lookahead = 50, tailSize = 0) shouldBe false
    }

    @Test
    fun `a short chapter is all tail`() {
        shouldPrefetchNextChapter(List(4) { false }, currentIndex = 0) shouldBe true
    }

    @Test
    fun `an index outside the chapter is clamped`() {
        shouldPrefetchNextChapter(pages(30, doneUntil = 7), currentIndex = -1) shouldBe true
        shouldPrefetchNextChapter(List(30) { false }, currentIndex = 100) shouldBe true
    }

    @Test
    fun `no lookahead and no tail only need the current page`() {
        val done = List(30) { it == 4 }
        shouldPrefetchNextChapter(done, currentIndex = 4, lookahead = 0, tailSize = 0) shouldBe true
        shouldPrefetchNextChapter(done, currentIndex = 5, lookahead = 0, tailSize = 0) shouldBe false
    }
}
