package haruka.reader

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class LongStripTagsTest {

    private fun tags(vararg genres: String) = LongStripTags.matches(genres.toList(), sourceName = null)

    @Test
    fun `webtoon, long strip, manhwa and manhua tags mean long strip`() {
        tags("Action", "Long Strip") shouldBe true
        tags("Webtoon") shouldBe true
        tags("Manhwa") shouldBe true
        tags("Manhua") shouldBe true
    }

    @Test
    fun `spelling variants are tolerated`() {
        tags("  long strip ") shouldBe true
        tags("LONG-STRIP") shouldBe true
        tags("Longstrip") shouldBe true
        tags("Long Strips") shouldBe true
        tags("Webtoons") shouldBe true
        tags("web_toon") shouldBe true
        tags("Manhwa (Korean)") shouldBe true
        tags("Type: Manhua") shouldBe true
        tags("Manwha") shouldBe true
    }

    @Test
    fun `colour, other strips and paged formats are not enough`() {
        tags("Full Color") shouldBe false
        tags("Full Color", "Action", "Romance") shouldBe false
        tags("Comic Strip") shouldBe false
        tags("Manga", "4-Koma", "Web Comic") shouldBe false
        tags("Long", "Strip") shouldBe false
    }

    @Test
    fun `words are matched whole`() {
        tags("Manhwaclan") shouldBe false
        tags("Webtoonish") shouldBe false
        tags("Belong Strip") shouldBe false
    }

    @Test
    fun `no or empty genres say nothing`() {
        LongStripTags.matches(null, sourceName = null) shouldBe false
        LongStripTags.matches(emptyList(), sourceName = null) shouldBe false
        tags("", " ") shouldBe false
    }

    @Test
    fun `source name counts when the genres say nothing`() {
        LongStripTags.matches(null, sourceName = "Webtoons.com") shouldBe true
        LongStripTags.matches(listOf("Action"), sourceName = "Manhwa18") shouldBe true
        LongStripTags.matches(listOf("Action"), sourceName = "MangaDex") shouldBe false
    }
}
