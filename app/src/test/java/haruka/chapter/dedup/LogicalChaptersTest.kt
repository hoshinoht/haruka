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
    fun `parts from a group that also has the whole chapter fold in as another copy`() {
        // ORV 264: one group uploaded 264 and 264.1/264.2.
        val result = group(
            chapter(1, 264.0, "Articuno"),
            chapter(2, 264.1, "Articuno"),
            chapter(3, 264.2, "Articuno"),
        )
        result.shape() shouldBe listOf(Triple(264.0, 0, listOf(listOf(1L), listOf(2L, 3L))))
    }

    @Test
    fun `dash-numbered parts sharing the whole chapter number merge in part order`() {
        // The Saintess Re...: the source numbers every part 21 and names them "Chapter 21 -1" .. "-4", newest first.
        val result = group(
            chapter(5, 21.0, "Official", name = "Chapter 21 -4"),
            chapter(4, 21.0, "Official", name = "Chapter 21 -3"),
            chapter(3, 21.0, "Official", name = "Chapter 21 -2"),
            chapter(2, 21.0, "Official", name = "Chapter 21 -1"),
            chapter(1, 21.0, "Official", name = "Chapter 21"),
        )
        result.shape() shouldBe listOf(Triple(21.0, 0, listOf(listOf(2L, 3L, 4L, 5L), listOf(1L))))
    }

    @Test
    fun `a dash range or a dash before a title is not a part`() {
        partIndex(chapter(1, 21.0, name = "Ch. 21-22")) shouldBe 0
        partIndex(chapter(2, 21.0, name = "Chapter 21 - 100 Days Later")) shouldBe 0
        partIndex(chapter(3, 21.0, name = "Chapter 21 - The Duke")) shouldBe 0
        partIndex(chapter(4, 21.0, name = "Chapter 21 -2")) shouldBe 2
        partIndex(chapter(5, 21.0, name = "Chapter 21 - 3: Reunion")) shouldBe 3
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
    fun `a repeated part collapses into its newest upload and the parts still merge`() {
        val result = group(
            chapter(1, 12.1, "Empoleon", dateUpload = 1),
            chapter(2, 12.1, "Empoleon", dateUpload = 5),
            chapter(3, 12.2, "Empoleon"),
            chapter(4, 12.0, "Ursaring"),
        )
        result.shape() shouldBe listOf(Triple(12.0, 0, listOf(listOf(2L, 3L), listOf(4L))))
        result.single().copies[0].duplicates.map { it.id } shouldBe listOf(1L)
    }

    @Test
    fun `repeats match on the normalized scanlator and ties keep the lowest source order`() {
        val result = group(
            chapter(1, 3.1, "Lua Comic", sourceOrder = 4),
            chapter(2, 3.1, "Lua_Comics", sourceOrder = 2),
            chapter(3, 3.2, "Lua Comic"),
        )
        result.shape() shouldBe listOf(Triple(3.0, 0, listOf(listOf(2L, 3L))))
        result.single().copies[0].duplicates.map { it.id } shouldBe listOf(1L)
    }

    @Test
    fun `a repeated part outside a mergeable run stays a copy of its part`() {
        group(chapter(1, 1.2, "A"), chapter(2, 1.2, "A")).shape() shouldBe listOf(
            Triple(1.0, 2, listOf(listOf(1L), listOf(2L))),
        )
    }

    @Test
    fun `a different kind keyword makes a separate logical chapter`() {
        val result = group(
            chapter(1, 3.0, "The Hours Between", name = "Spin-Off #3 (Episode 99)"),
            chapter(2, 3.0, "The Hours Between", name = "Episode 3"),
            chapter(3, 3.0, "Group B", name = "Ch. 3"),
            chapter(4, 35.3, "official", name = "Ch. 35.3 - Epilogue 3"),
            chapter(5, 35.3, "unofficial", name = "Ch. 35.3 - Side Story (3)"),
        )
        result.map { Triple(it.number, it.kind, it.copies.map { copy -> copy.ids }) } shouldBe listOf(
            Triple(3.0, ChapterKind.SPIN_OFF, listOf(listOf(1L))),
            Triple(3.0, ChapterKind.MAIN, listOf(listOf(2L), listOf(3L))),
            Triple(35.0, ChapterKind.EPILOGUE, listOf(listOf(4L))),
            Triple(35.0, ChapterKind.SIDE_STORY, listOf(listOf(5L))),
        )
    }

    @Test
    fun `split parts merge within their kind`() {
        val result = group(
            chapter(1, 23.1, "A", name = "Ch. 23.1 - Side Story: The City"),
            chapter(2, 23.2, "A", name = "Ch. 23.2 - Side Story: The City 2"),
            chapter(3, 23.0, "B", name = "Chapter 23"),
        )
        result.map { Triple(it.number, it.kind, it.copies.map { copy -> copy.ids }) } shouldBe listOf(
            Triple(23.0, ChapterKind.SIDE_STORY, listOf(listOf(1L, 2L))),
            Triple(23.0, ChapterKind.MAIN, listOf(listOf(3L))),
        )
    }

    @Test
    fun `kind keywords are whole words`() {
        mapOf(
            "Spin-Off #4" to ChapterKind.SPIN_OFF,
            "Spinoff 2" to ChapterKind.SPIN_OFF,
            "Ch. 5 Side-Story" to ChapterKind.SIDE_STORY,
            "Ch. 5 SS 2" to ChapterKind.SIDE_STORY,
            "Vol. 6 Ch. 32.3 Extra Chapter" to ChapterKind.EXTRA,
            "Volume Extras" to ChapterKind.EXTRA,
            "Prologue" to ChapterKind.PROLOGUE,
            "Omake" to ChapterKind.OMAKE,
            "Chapter 38 - Special Episode" to ChapterKind.SPECIAL,
            "Bonus" to ChapterKind.BONUS,
            "Afterword" to ChapterKind.AFTERWORD,
            "Illust" to ChapterKind.ILLUSTRATION,
            "Hiatus Notice" to ChapterKind.ANNOUNCEMENT,
            "Chapter 12" to ChapterKind.MAIN,
            "Ch. 6 Boss Fight" to ChapterKind.MAIN,
            "Extraordinary" to ChapterKind.MAIN,
            "Specialist" to ChapterKind.MAIN,
        ).forEach { (name, kind) -> chapterKind(name) shouldBe kind }
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
