package haruka.updates

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ChapterLinePartsTest {

    @Test
    fun `trailing parenthesised scanlator becomes the extra note`() {
        chapterLineParts("Chapter 18.2 (Flaemes TL)", null) shouldBe
            ChapterLineParts("Chapter 18.2", "(Flaemes TL)")
    }

    @Test
    fun `nested parentheses stay in one note`() {
        chapterLineParts("Ch. 5 (part (2))", null) shouldBe ChapterLineParts("Ch. 5", "(part (2))")
    }

    @Test
    fun `a name that is only a parenthesis is left whole`() {
        chapterLineParts("(Oneshot)", null) shouldBe ChapterLineParts("(Oneshot)", null)
    }

    @Test
    fun `scanlator is added unless the name already mentions it`() {
        chapterLineParts("Vol.1 Ch.11", "Kirei Cake") shouldBe ChapterLineParts("Vol.1 Ch.11", "Kirei Cake")
        chapterLineParts("Ch. 139 UWAKI SCANS", "Uwaki Scans") shouldBe ChapterLineParts("Ch. 139 UWAKI SCANS", null)
        chapterLineParts("Chapter 3 (Foo)", "Bar") shouldBe ChapterLineParts("Chapter 3", "(Foo) · Bar")
    }

    @Test
    fun `blank scanlator and plain names have no note`() {
        chapterLineParts("Chapter 7", " ") shouldBe ChapterLineParts("Chapter 7", null)
    }
}
