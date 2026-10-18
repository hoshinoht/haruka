package haruka.chapter.dedup

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class DuplicateCopiesTest {

    private val chapters = listOf(
        chapter(1, 1.0, "Group A"),
        chapter(2, 1.0, "VIZ"),
        chapter(3, 2.1, "Group A"),
        chapter(4, 2.2, "Group A"),
        chapter(5, 2.0, "VIZ"),
        chapter(6, 3.0, "Group A"),
    )

    private fun toMarkRead(newlyRead: Set<Long>, read: Set<Long> = emptySet(), merge: Boolean = true) =
        duplicateCopiesToMarkRead(chapters, merge, newlyRead) { it in read }

    @Test
    fun `reading a copy marks the other copies of its chapter`() {
        toMarkRead(setOf(2)) shouldBe setOf(1L)
    }

    @Test
    fun `copies already read are left alone`() {
        toMarkRead(setOf(2), read = setOf(1)) shouldBe emptySet()
    }

    @Test
    fun `a split copy marks the others only once its last part is read`() {
        toMarkRead(setOf(3)) shouldBe emptySet()
        toMarkRead(setOf(4), read = setOf(3)) shouldBe setOf(5L)
        toMarkRead(setOf(3, 4)) shouldBe setOf(5L)
    }

    @Test
    fun `finishing a whole copy marks every part of a split copy`() {
        toMarkRead(setOf(5)) shouldBe setOf(3L, 4L)
    }

    @Test
    fun `without split merging, parts are chapters of their own`() {
        toMarkRead(setOf(5), merge = false) shouldBe emptySet()
    }

    @Test
    fun `a chapter with a single copy marks nothing`() {
        toMarkRead(setOf(6)) shouldBe emptySet()
    }

    @Test
    fun `new chapters are dropped when another copy of their chapter is downloaded`() {
        chaptersWithDownloadedCopy(chapters, true, candidates = setOf(1, 3, 4, 6), downloaded = setOf(2, 5)) shouldBe
            setOf(1L, 3L, 4L)
    }

    @Test
    fun `a partly downloaded split copy does not count as downloaded`() {
        chaptersWithDownloadedCopy(chapters, true, candidates = setOf(5), downloaded = setOf(3)) shouldBe emptySet()
        chaptersWithDownloadedCopy(chapters, true, candidates = setOf(5), downloaded = setOf(3, 4)) shouldBe setOf(5L)
    }

    @Test
    fun `the reader keeps winners, and the copy being read`() {
        val deduped = ChapterDeduper(testCatalog).dedupe(chapters)
        readerChapterIds(deduped, currentId = 6) shouldBe setOf(2L, 5L, 6L)
        readerChapterIds(deduped, currentId = 1) shouldBe setOf(1L, 5L, 6L)
        readerChapterIds(deduped, currentId = 4) shouldBe setOf(2L, 3L, 4L, 6L)
    }
}
