package haruka.reader.loading

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PagePlanTest {

    // Upstream's plan around page 10 with chunks 3 to 5: 10, 9, 11, then forward, then backward
    private val plan = listOf(listOf(10, 9, 11), listOf(12, 13, 14, 15), listOf(16, 17, 18, 19, 20), listOf(8, 7))

    @Test
    fun `no plan covers nothing`() {
        isCoveredByPlan(emptyList(), 0) shouldBe false
    }

    @Test
    fun `pages in the first two chunks are covered`() {
        for (index in listOf(9, 10, 11, 12, 15)) {
            isCoveredByPlan(plan, index) shouldBe true
        }
    }

    @Test
    fun `pages in later chunks need a new plan`() {
        isCoveredByPlan(plan, 16) shouldBe false
        isCoveredByPlan(plan, 8) shouldBe false
    }

    @Test
    fun `pages outside the plan need a new plan`() {
        isCoveredByPlan(plan, 0) shouldBe false
        isCoveredByPlan(plan, -1) shouldBe false
        isCoveredByPlan(plan, 100) shouldBe false
    }

    @Test
    fun `a plan shorter than the leading chunks is covered as far as it goes`() {
        isCoveredByPlan(listOf(listOf(0)), 0) shouldBe true
        isCoveredByPlan(listOf(listOf(0)), 1) shouldBe false
    }

    @Test
    fun `the leading chunk count is respected`() {
        isCoveredByPlan(plan, 12, leadingChunks = 1) shouldBe false
        isCoveredByPlan(plan, 16, leadingChunks = 3) shouldBe true
        isCoveredByPlan(plan, 10, leadingChunks = 0) shouldBe false
    }

    @Test
    fun `one parallel download keeps upstream's chunks`() {
        preloadChunkSizes(1) shouldBe (1 to 1)
    }

    @Test
    fun `more parallel downloads start a burst that grows by one`() {
        preloadChunkSizes(2) shouldBe (2 to 3)
        preloadChunkSizes(3) shouldBe (3 to 4)
    }

    @Test
    fun `out of range settings are clamped`() {
        preloadChunkSizes(0) shouldBe (1 to 1)
        preloadChunkSizes(-5) shouldBe (1 to 1)
        preloadChunkSizes(9) shouldBe (3 to 4)
    }
}
