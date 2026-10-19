package haruka.chapter.ui

import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.ui.manga.ChapterList
import haruka.chapter.dedup.ChapterDeduper
import haruka.chapter.dedup.DedupOptions
import haruka.chapter.dedup.OfficialCatalog
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import java.io.File

class MangaChapterGroupsTest {

    // The bundled catalog lives in :haruka:core; unit tests run with this module as working dir.
    private val deduper = ChapterDeduper(
        OfficialCatalog.parse(File("../haruka/core/src/main/assets/${OfficialCatalog.ASSET_PATH}").readText()),
    )
    private val expansion = ChapterGroupExpansion()

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
