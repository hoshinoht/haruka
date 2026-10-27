package haruka.chapter.ui

import haruka.chapter.dedup.ChapterDeduper
import haruka.chapter.dedup.DedupChapter
import haruka.chapter.dedup.DedupOptions
import haruka.chapter.dedup.DedupRule
import haruka.chapter.dedup.chapter
import haruka.chapter.dedup.testCatalog
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ChapterGroupsTest {

    private val deduper = ChapterDeduper(testCatalog)
    private val expansion = ChapterGroupExpansion()

    private fun groups(vararg chapters: DedupChapter, downloaded: Set<Long> = emptySet()) =
        buildChapterGroups(chapters.toList(), deduper, DedupOptions()) { it.id in downloaded }

    /** Rows as "id" for plain chapters, "id[versions/parts]" for heads, ">id" for revealed rows. */
    private fun List<ChapterGroup>.render(expanded: Set<Long> = emptySet()) = toRows(expanded, expansion) { id, row ->
        when {
            row == null -> "$id"
            row.head -> "$id[${row.versions}/${row.parts}]"
            else -> ">$id"
        }
    }

    @Test
    fun `a head marks a downloaded copy it hides, until the group is expanded`() {
        // Official first, so the downloaded fan copy stays hidden under the VIZ head
        val officialFirst = DedupOptions(rules = listOf(DedupRule.OFFICIAL) + (DedupRule.DEFAULT - DedupRule.OFFICIAL))
        val chapters = listOf(chapter(1, 1.0, "Group A"), chapter(2, 1.0, "VIZ"), chapter(3, 2.0, "VIZ"))
        val groups = buildChapterGroups(chapters, deduper, officialFirst) { it.id == 1L }
        groups.map { it.head to it.hiddenDownloaded } shouldBe listOf(2L to true, 3L to false)
        groups.toRows(emptySet(), expansion) { id, row -> id to row?.hiddenDownloaded }
            .shouldBe(listOf(2L to true, 3L to null))
        // The row keeps the flag while expanded; the chip hides the mark because the copy is on screen
        groups.toRows(setOf(1L), expansion) { id, row -> id to row?.hiddenDownloaded }
            .shouldBe(listOf(2L to true, 1L to false, 3L to null))
    }

    @Test
    fun `a downloaded copy that wins its group is not marked, a half-downloaded split winner is`() {
        groups(chapter(1, 1.0, "Group A"), chapter(2, 1.0, "VIZ"), downloaded = setOf(1))
            .single().let { it.head to it.hiddenDownloaded } shouldBe (1L to false)
        groups(chapter(10, 3.1, "Group A"), chapter(11, 3.2, "Group A"), downloaded = setOf(10))
            .single().hiddenDownloaded shouldBe true
        groups(chapter(10, 3.1, "Group A"), chapter(11, 3.2, "Group A"), downloaded = setOf(10, 11))
            .single().hiddenDownloaded shouldBe false
    }

    @Test
    fun `five copies collapse to the winner with a versions count`() {
        val groups = groups(
            chapter(1, 1.0, "Group A"),
            chapter(2, 1.0, "Group B"),
            chapter(3, 1.0, "VIZ"),
            chapter(4, 1.0, "Group C"),
            chapter(5, 1.0, "Group D"),
            chapter(6, 2.0, "Group A"),
        )
        groups.render() shouldBe listOf("3[5/1]", "6")
        groups.first().key shouldBe 1L
    }

    @Test
    fun `the winner takes the position of its logical chapter in the sorted list`() {
        val groups = groups(
            chapter(4, 2.0, "Group A"),
            chapter(1, 1.0, "Group A"),
            chapter(5, 3.0, "Group A"),
            chapter(2, 1.0, "VIZ"),
        )
        groups.render() shouldBe listOf("4", "2[2/1]", "5")
    }

    @Test
    fun `a merged split winner shows its first part and expands to its parts, then the other copies`() {
        val groups = groups(
            chapter(12, 3.0, "Group B"),
            chapter(11, 3.2, "Group A"),
            chapter(10, 3.1, "Group A"),
            downloaded = setOf(10, 11),
        )
        groups.render() shouldBe listOf("10[2/2]")
        groups.render(expanded = setOf(10)) shouldBe listOf("10[2/2]", ">11", ">12")
    }

    @Test
    fun `a split winner is headed by its first unread part, part 1 once every part is read`() {
        val groups = groups(
            chapter(13, 3.0, "Group B"),
            chapter(12, 3.3, "Group A"),
            chapter(11, 3.2, "Group A"),
            chapter(10, 3.1, "Group A"),
            downloaded = setOf(10, 11, 12),
        )
        val partOneRead = groups.headedAtFirstUnread { it == 10L }
        partOneRead.render() shouldBe listOf("11[2/3]")
        partOneRead.render(expanded = setOf(10)) shouldBe listOf("11[2/3]", ">10", ">12", ">13")
        groups.headedAtFirstUnread { it == 10L || it == 11L }.render() shouldBe listOf("12[2/3]")
        groups.headedAtFirstUnread { it != 13L }.render() shouldBe listOf("10[2/3]")

        val selected = mutableSetOf(11L)
        syncWinnerSelection(emptySet(), selected, partOneRead)
        selected shouldBe setOf(10L, 11L, 12L)
    }

    @Test
    fun `a repeated part upload is revealed with the winner's parts before other copies`() {
        val groups = groups(
            chapter(13, 3.0, "Group B"),
            chapter(10, 3.1, "Group A"),
            chapter(11, 3.2, "Group A", dateUpload = 5),
            chapter(12, 3.2, "Group A", dateUpload = 1),
            downloaded = setOf(10, 11),
        )
        groups.render(expanded = setOf(10)) shouldBe listOf("10[2/2]", ">11", ">12", ">13")
    }

    @Test
    fun `expansion is keyed by group and toggles`() {
        val groups = groups(chapter(1, 1.0, "Group A"), chapter(2, 1.0, "VIZ"), chapter(3, 2.0, "Group A"))
        expansion.toggle(groups.first().key)
        groups.render(expansion.expanded.value) shouldBe listOf("2[2/1]", ">1", "3")
        expansion.toggle(groups.first().key)
        groups.render(expansion.expanded.value) shouldBe listOf("2[2/1]", "3")
    }

    @Test
    fun `selecting a head selects its winner's parts and deselecting it drops them`() {
        val groups = groups(chapter(10, 3.1, "Group A"), chapter(11, 3.2, "Group A"), chapter(12, 4.0, "Group A"))
        val selected = mutableSetOf(10L)
        syncWinnerSelection(emptySet(), selected, groups)
        selected shouldBe setOf(10L, 11L)

        val deselected = mutableSetOf(12L)
        syncWinnerSelection(setOf(10L, 11L, 12L), deselected, groups)
        deselected shouldBe setOf(12L)
    }

    @Test
    fun `a part selected on its own stays selected while its head is untouched`() {
        val groups = groups(chapter(10, 3.1, "Group A"), chapter(11, 3.2, "Group A"))
        val selected = mutableSetOf(11L)
        syncWinnerSelection(emptySet(), selected, groups)
        selected shouldBe setOf(11L)
    }

    @Test
    fun `a head marks every copy and part of its chapter, other rows only themselves`() {
        val groups = groups(
            chapter(10, 3.1, "Group A"),
            chapter(11, 3.2, "Group A"),
            chapter(12, 3.0, "Group B"),
            chapter(13, 4.0, "Group A"),
            downloaded = setOf(10, 11),
        )
        val byHead = groups.filter { it.isGrouped }.associateBy { it.head }
        withGroupCopies(listOf(10L), byHead) shouldBe setOf(10L, 11L, 12L)
        withGroupCopies(listOf(12L, 13L), byHead) shouldBe setOf(12L, 13L)
    }
}
