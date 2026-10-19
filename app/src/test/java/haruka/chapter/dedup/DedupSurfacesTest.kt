package haruka.chapter.dedup

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter

class DedupSurfacesTest {

    @Test
    fun `a split winner is read in part order even when every part has the whole chapter number`() {
        // Sorted by number, the tie leaves the source's newest-first order: 22, -4, -3, -2, -1, 20.
        val chapters = listOf(
            chapter(9, 22.0, "Chapter 22"),
            chapter(5, 21.0, "Chapter 21 -4"),
            chapter(4, 21.0, "Chapter 21 -3"),
            chapter(3, 21.0, "Chapter 21 -2"),
            chapter(2, 21.0, "Chapter 21 -1"),
            chapter(8, 20.0, "Chapter 20"),
        )
        val deduped = ChapterDeduper(testCatalog).dedupe(chapters.map { it.toDedupChapter() })
        inPartOrder(chapters, deduped).map { it.id } shouldBe listOf(9L, 2L, 3L, 4L, 5L, 8L)
    }

    private fun chapter(id: Long, number: Double, name: String) = Chapter.create()
        .copy(id = id, name = name, chapterNumber = number, scanlator = "Official", sourceOrder = id)
}
