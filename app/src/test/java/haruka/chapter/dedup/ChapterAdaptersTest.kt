package haruka.chapter.dedup

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import kotlin.time.Instant

class ChapterAdaptersTest {

    @Test
    fun `adapter maps domain chapters and blanks scanlator`() {
        val domain = Chapter.create().copy(
            id = 7,
            name = "Ch. 3",
            chapterNumber = 3.0,
            scanlator = " ",
            dateUpload = Instant.fromEpochMilliseconds(42),
            sourceOrder = 5,
        )
        domain.toDedupChapter() shouldBe DedupChapter(7, "Ch. 3", 3.0, null, 42, 5)
        domain.copy(dateUpload = null, scanlator = "A").toDedupChapter().let {
            it.dateUpload shouldBe 0
            it.scanlator shouldBe "A"
        }
        // Backup restore widens Float chapter numbers; the adapter recovers the decimal dedup compares.
        domain.copy(chapterNumber = 6.1f.toDouble()).toDedupChapter().chapterNumber shouldBe 6.1
    }
}
