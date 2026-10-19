package haruka.updates

import io.kotest.matchers.shouldBe
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import org.junit.jupiter.api.Test

class UpdatesGroupingTest {

    private data class Update(val chapterId: Long, val mangaId: Long, val date: LocalDate)

    private val today = LocalDate(2026, 10, 19)
    private val yesterday = today.minus(DatePeriod(days = 1))

    private fun group(vararg updates: Update) = groupUpdates(updates.toList(), { it.date }, { it.mangaId })

    @Test
    fun `interleaved chapters of one manga on one day form one group in first-appearance order`() {
        val rows = group(
            Update(1, mangaId = 10, today),
            Update(2, mangaId = 20, today),
            Update(3, mangaId = 10, today),
            Update(4, mangaId = 20, today),
        )

        rows shouldBe listOf(
            GroupedRow.Header(today, chapterCount = 4),
            GroupedRow.Group(GroupKey(today, 10), listOf(Update(1, 10, today), Update(3, 10, today))),
            GroupedRow.Group(GroupKey(today, 20), listOf(Update(2, 20, today), Update(4, 20, today))),
        )
    }

    @Test
    fun `same manga on different days stays in separate groups under each day`() {
        val rows = group(
            Update(1, mangaId = 10, today),
            Update(2, mangaId = 10, yesterday),
        )

        rows shouldBe listOf(
            GroupedRow.Header(today, chapterCount = 1),
            GroupedRow.Group(GroupKey(today, 10), listOf(Update(1, 10, today))),
            GroupedRow.Header(yesterday, chapterCount = 1),
            GroupedRow.Group(GroupKey(yesterday, 10), listOf(Update(2, 10, yesterday))),
        )
    }

    @Test
    fun `every input item appears exactly once`() {
        val updates = (0L until 200L).map {
            Update(it, mangaId = it % 7, today.minus(DatePeriod(days = (it / 50).toInt())))
        }

        val chapters = groupUpdates(updates, { it.date }, { it.mangaId })
            .filterIsInstance<GroupedRow.Group<Update>>()
            .flatMap { it.items }

        chapters.map { it.chapterId }.sorted() shouldBe updates.map { it.chapterId }
    }

    @Test
    fun `leader is the newest chapter and hidden holds the rest`() {
        val rows = group(Update(1, 10, today), Update(2, 10, today), Update(3, 10, today))
        val group = rows[1] as GroupedRow.Group<Update>

        group.leader.chapterId shouldBe 1
        group.hidden.map { it.chapterId } shouldBe listOf(2L, 3L)
    }

    @Test
    fun `empty input yields no rows`() {
        group() shouldBe emptyList()
    }
}
