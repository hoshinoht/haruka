package haruka.chapter.dedup

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter

class LogicalChaptersTest {

    private fun group(vararg chapters: DedupChapter, merge: Boolean = true) =
        groupLogicalChapters(chapters.toList(), merge)

    /** (number, part, ids of each copy) per logical chapter. */
    private fun List<LogicalChapter>.shape() = map { Triple(it.number, it.part, it.copies.map { copy -> copy.ids }) }

    @Test
    fun `parts 1_1 to 1_4 from one group form one logical chapter 1`() {
        val result = group(
            chapter(1, 1.1, "A"),
            chapter(2, 1.2, "A"),
            chapter(3, 1.3, "A"),
            chapter(4, 1.4, "A"),
        )
        result.shape() shouldBe listOf(Triple(1.0, 0, listOf(listOf(1L, 2L, 3L, 4L))))
    }

    @Test
    fun `group A chapter 1 and group B parts 1_1 to 1_3 are two copies of chapter 1`() {
        val result = group(
            chapter(1, 1.0, "A"),
            chapter(2, 1.1, "B"),
            chapter(3, 1.2, "B"),
            chapter(4, 1.3, "B"),
        )
        result.shape() shouldBe listOf(Triple(1.0, 0, listOf(listOf(1L), listOf(2L, 3L, 4L))))
    }

    @Test
    fun `chapter numbers widened from a backup float still merge and meet fresh copies`() {
        // Restore writes Float.toDouble(), so 6.1 arrives as 6.099999904632568; a later update writes a true 6.3.
        fun restored(id: Long, number: Float, scanlator: String) = Chapter.create()
            .copy(id = id, name = "Ch. $number", chapterNumber = number.toDouble(), scanlator = scanlator)
            .toDedupChapter()
        val result = group(
            restored(1, 6.1f, "A"),
            restored(2, 6.2f, "A"),
            chapter(3, 6.0, "B"),
            restored(4, 6.3f, "B"),
            chapter(5, 6.3, "C"),
        )
        result.shape() shouldBe listOf(
            Triple(6.0, 0, listOf(listOf(1L, 2L), listOf(3L))),
            Triple(6.0, 3, listOf(listOf(4L), listOf(5L))),
        )
        3.25.withoutFloatNoise() shouldBe 3.25
        6.1.withoutFloatNoise() shouldBe 6.1
        (-1.0).withoutFloatNoise() shouldBe -1.0
    }

    @Test
    fun `a lone 1_5 stays its own chapter`() {
        val result = group(chapter(1, 1.0, "A"), chapter(2, 1.5, "A"), chapter(3, 1.5, "B"))
        result.shape() shouldBe listOf(
            Triple(1.0, 0, listOf(listOf(1L))),
            Triple(1.0, 5, listOf(listOf(2L), listOf(3L))),
        )
    }

    @Test
    fun `only the run starting at 1 merges, a trailing extra stays separate`() {
        val result = group(chapter(1, 1.1, "A"), chapter(2, 1.2, "A"), chapter(3, 1.5, "A"))
        result.shape() shouldBe listOf(
            Triple(1.0, 0, listOf(listOf(1L, 2L))),
            Triple(1.0, 5, listOf(listOf(3L))),
        )
    }

    @Test
    fun `parts from a group that also has the whole chapter stay unmerged`() {
        val result = group(chapter(1, 1.0, "A"), chapter(2, 1.1, "A"), chapter(3, 1.2, "A"))
        result.shape() shouldBe listOf(
            Triple(1.0, 0, listOf(listOf(1L))),
            Triple(1.0, 1, listOf(listOf(2L))),
            Triple(1.0, 2, listOf(listOf(3L))),
        )
    }

    @Test
    fun `parts merge per scanlator`() {
        val result = group(chapter(1, 1.1, "A"), chapter(2, 1.2, "B"))
        result.shape() shouldBe listOf(
            Triple(1.0, 1, listOf(listOf(1L))),
            Triple(1.0, 2, listOf(listOf(2L))),
        )
    }

    @Test
    fun `a run not starting at 1 does not merge`() {
        group(chapter(1, 1.2, "A"), chapter(2, 1.3, "A")).shape() shouldBe listOf(
            Triple(1.0, 2, listOf(listOf(1L))),
            Triple(1.0, 3, listOf(listOf(2L))),
        )
    }

    @Test
    fun `a repeated part index blocks the merge`() {
        group(chapter(1, 1.1, "A"), chapter(2, 1.2, "A"), chapter(3, 1.2, "A")).shape() shouldBe listOf(
            Triple(1.0, 1, listOf(listOf(1L))),
            Triple(1.0, 2, listOf(listOf(2L), listOf(3L))),
        )
    }

    @Test
    fun `1_1 and 1_10 stay distinct when names say so`() {
        val result = group(
            chapter(1, 1.1, "A", name = "Chapter 1.1"),
            chapter(2, 1.1, "B", name = "Chapter 1.10"),
        )
        result.shape() shouldBe listOf(
            Triple(1.0, 1, listOf(listOf(1L))),
            Triple(1.0, 10, listOf(listOf(2L))),
        )
    }

    @Test
    fun `parts 1_1 to 1_10 from names merge in part order`() {
        val parts = (1..10).map { part ->
            chapter(part.toLong(), "1.$part".toDouble(), "A", name = "Chapter 1.$part")
        }
        val result = groupLogicalChapters(parts.reversed(), mergeSplitChapters = true)
        result.shape() shouldBe listOf(Triple(1.0, 0, listOf((1L..10L).toList())))
    }

    @Test
    fun `part index comes from Part and Pt names`() {
        partIndex(chapter(1, 12.0, name = "Ch. 12 Part 3")) shouldBe 3
        partIndex(chapter(1, 12.3, name = "Chapter 12 - Part 3")) shouldBe 3
        partIndex(chapter(1, 12.2, name = "Pt. 2")) shouldBe 2
        partIndex(chapter(1, 1.1, name = "Pt. 10")) shouldBe 10
        partIndex(chapter(1, 12.1, name = "Chapter 12.10")) shouldBe 10
        partIndex(chapter(1, 12.1, name = "Side story")) shouldBe 1
    }

    @Test
    fun `names that disagree with the number are ignored`() {
        partIndex(chapter(1, 5.0, name = "Vol. 2 Part 1 Chapter 5")) shouldBe 0
        partIndex(chapter(1, 12.0, name = "Ch. 11 Part 3")) shouldBe 0
        partIndex(chapter(1, 1.1, name = "Chapter 1.5")) shouldBe 1
        partIndex(chapter(1, 1.05, name = "Chapter 1.05")) shouldBe 0
        partIndex(chapter(1, 7.0)) shouldBe 0
    }

    @Test
    fun `Part-named chapters with a whole number merge into that chapter`() {
        val result = group(
            chapter(1, 12.0, "A", name = "Ch. 12 Part 1"),
            chapter(2, 12.0, "A", name = "Ch. 12 Part 2"),
            chapter(3, 12.0, "B", name = "Ch. 12"),
        )
        result.shape() shouldBe listOf(Triple(12.0, 0, listOf(listOf(1L, 2L), listOf(3L))))
    }

    @Test
    fun `toggle off keys every chapter by its number and merges nothing`() {
        val result = group(
            chapter(1, 1.1, "A", name = "Chapter 1.1"),
            chapter(2, 1.2, "A"),
            chapter(3, 1.1, "B", name = "Chapter 1.10"),
            chapter(4, 1.0, "B"),
            merge = false,
        )
        result.shape() shouldBe listOf(
            Triple(1.1, 0, listOf(listOf(1L), listOf(3L))),
            Triple(1.2, 0, listOf(listOf(2L))),
            Triple(1.0, 0, listOf(listOf(4L))),
        )
    }

    @Test
    fun `unknown numbers are never grouped or merged`() {
        listOf(true, false).forEach { merge ->
            group(chapter(1, -1.0, "A"), chapter(2, -1.0, "A"), chapter(3, -1.0, "B"), merge = merge)
                .shape() shouldBe listOf(
                Triple(-1.0, 0, listOf(listOf(1L))),
                Triple(-1.0, 0, listOf(listOf(2L))),
                Triple(-1.0, 0, listOf(listOf(3L))),
            )
        }
    }

    @Test
    fun `logical chapters keep the order of first appearance`() {
        val result = group(
            chapter(1, 3.0, "A"),
            chapter(2, 1.2, "A"),
            chapter(3, 2.0, "A"),
            chapter(4, 1.1, "A"),
            chapter(5, 3.0, "B"),
        )
        result.shape() shouldBe listOf(
            Triple(3.0, 0, listOf(listOf(1L), listOf(5L))),
            Triple(1.0, 0, listOf(listOf(4L, 2L))),
            Triple(2.0, 0, listOf(listOf(3L))),
        )
    }

    @Test
    fun `adapter maps domain chapters and blanks scanlator`() {
        val domain = tachiyomi.domain.chapter.model.Chapter.create().copy(
            id = 7,
            name = "Ch. 3",
            chapterNumber = 3.0,
            scanlator = " ",
            dateUpload = kotlin.time.Instant.fromEpochMilliseconds(42),
            sourceOrder = 5,
        )
        domain.toDedupChapter() shouldBe DedupChapter(7, "Ch. 3", 3.0, null, 42, 5)
        domain.copy(dateUpload = null, scanlator = "A").toDedupChapter().let {
            it.dateUpload shouldBe 0
            it.scanlator shouldBe "A"
        }
    }
}
