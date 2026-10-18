package haruka.chapter.ui

import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.ui.manga.ChapterList
import haruka.chapter.dedup.ChapterDeduper
import haruka.chapter.dedup.DedupChapter
import haruka.chapter.dedup.DedupOptions
import haruka.chapter.dedup.chapter
import haruka.chapter.dedup.testCatalog
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter

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

    @Test
    fun `list items group by their download state and keep hidden winner parts selectable`() {
        val input = ChapterGroupsInput(DedupOptions(), emptySet(), expansion, deduper)
        val list = input.build(
            listOf(
                item(1, 5.0, "VIZ"),
                item(2, 5.1, "Group A", Download.State.DOWNLOADED),
                item(3, 5.2, "Group A", Download.State.DOWNLOADED),
            ),
        )
        list.rows.map { it.id } shouldBe listOf(2L)
        list.rows.single().harukaRow?.versions shouldBe 2
        list.selectable.map { it.id } shouldBe listOf(2L, 3L)
    }

    @Test
    fun `scale - grouped rows for 1,005 chapters x 4 groups build in under 20 ms`() {
        val items = benchmarkItems(1_005)
        val input =
            ChapterGroupsInput(DedupOptions(rankedScanlators = listOf("Lua Comic")), emptySet(), expansion, deduper)
        repeat(WARMUP) { input.build(items) }
        val samples = DoubleArray(RUNS) {
            val start = System.nanoTime()
            input.build(items)
            (System.nanoTime() - start) / 1_000_000.0
        }
        samples.sort()
        val millis = samples[RUNS / 2]
        println("ChapterGroupsInput: ${items.size} chapters (1,005 x 4 groups) built in median $millis ms")
        millis shouldBeLessThan 20.0
    }

    private fun item(
        id: Long,
        number: Double,
        scanlator: String,
        state: Download.State = Download.State.NOT_DOWNLOADED,
        sourceOrder: Long = id,
    ) = ChapterList.Item(
        chapter = Chapter.create().copy(
            id = id,
            name = "Chapter $number",
            chapterNumber = number,
            scanlator = scanlator,
            sourceOrder = sourceOrder,
        ),
        downloadState = state,
    )

    /** Newest first like the default list; one group splits every tenth chapter into three parts. */
    private fun benchmarkItems(count: Int): List<ChapterList.Item> {
        val groups = listOf("Official", "Group A", "Group B & Group C", "Lua Comics")
        var id = 0L
        val items = ArrayList<ChapterList.Item>(count * groups.size + count)
        for (number in count downTo 1) {
            groups.forEachIndexed { group, scanlator ->
                val state = if (number % 7 == 0) Download.State.DOWNLOADED else Download.State.NOT_DOWNLOADED
                if (group == 3 && number % 10 == 0) {
                    for (part in 3 downTo 1) items += item(++id, "$number.$part".toDouble(), scanlator, state)
                } else {
                    items += item(++id, number.toDouble(), scanlator, state)
                }
            }
        }
        return items
    }

    private companion object {
        const val WARMUP = 10
        const val RUNS = 11
    }
}
