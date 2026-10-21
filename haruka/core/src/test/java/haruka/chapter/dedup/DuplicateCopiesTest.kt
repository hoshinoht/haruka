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

    /** Group A's 7.2 uploaded twice (id 12 older): one copy 10 + 11 with 12 as its repeated upload. */
    private val repeated = listOf(
        chapter(10, 7.1, "Group A"),
        chapter(11, 7.2, "Group A", dateUpload = 5),
        chapter(12, 7.2, "Group A", dateUpload = 1),
    )

    @Test
    fun `finishing a copy marks its repeated part uploads read`() {
        duplicateCopiesToMarkRead(repeated, true, setOf(11)) { it == 10L } shouldBe setOf(12L)
        duplicateCopiesToMarkRead(repeated, true, setOf(10)) { false } shouldBe emptySet()
    }

    @Test
    fun `a repeated part upload is not downloaded once its copy is`() {
        chaptersWithDownloadedCopy(repeated, true, candidates = setOf(12), downloaded = setOf(10, 11)) shouldBe
            setOf(12L)
        chaptersWithDownloadedCopy(repeated, true, candidates = setOf(12), downloaded = setOf(10)) shouldBe emptySet()
    }

    @Test
    fun `reading a repeated part upload swaps it in for that part`() {
        val deduped = ChapterDeduper(testCatalog).dedupe(repeated)
        readerChapterIds(deduped, currentId = 11) shouldBe setOf(10L, 11L)
        readerChapterIds(deduped, currentId = 12) shouldBe setOf(10L, 12L)
    }

    private fun nextUnread(chapters: List<DedupChapter>, read: Set<Long> = emptySet(), fromEnd: Boolean = false) =
        nextUnreadChapterId(ChapterDeduper(testCatalog).dedupe(chapters), fromEnd) { it in read }

    @Test
    fun `continue reading opens the preferred copy, not the first upload`() {
        nextUnread(chapters) shouldBe 2L
    }

    @Test
    fun `a chapter read in any copy is skipped`() {
        nextUnread(chapters, read = setOf(1)) shouldBe 5L
        nextUnread(chapters, read = setOf(3, 4)) shouldBe 2L
        nextUnread(chapters, read = setOf(1, 3, 4)) shouldBe 6L
    }

    @Test
    fun `a descending list starts from its end`() {
        nextUnread(chapters.asReversed(), fromEnd = true) shouldBe 2L
        nextUnread(chapters.asReversed(), read = setOf(2), fromEnd = true) shouldBe 5L
    }

    @Test
    fun `a split winner resumes at its first unread part`() {
        nextUnread(repeated, read = setOf(10)) shouldBe 11L
    }

    @Test
    fun `nothing left when every chapter is read`() {
        nextUnread(chapters, read = setOf(1, 5, 6)) shouldBe null
    }
}
