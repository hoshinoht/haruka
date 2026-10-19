package haruka.library

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ShelvesTest {

    private val now = 1_800_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    private fun entry(
        id: Long,
        hasStarted: Boolean = true,
        unreadCount: Long = 0,
        lastRead: Long? = null,
        chapterFetchedAt: Long? = null,
        favoriteAt: Long? = null,
    ) = ShelfInput(
        id = id,
        title = "Title $id",
        lastRead = lastRead,
        lastUpdate = null,
        latestUpload = null,
        chapterFetchedAt = chapterFetchedAt,
        favoriteAt = favoriteAt,
        unreadCount = unreadCount,
        totalChapters = unreadCount,
        hasStarted = hasStarted,
    )

    // Started and caught up: qualifies for no shelf, only pads the library past the minimum.
    private fun filler(count: Int, from: Long = 1000) = (0 until count).map { entry(from + it) }

    @Test
    fun `hidden below 20 series`() {
        val library = listOf(entry(1, hasStarted = false)) + filler(18)
        buildShelves(library, now).shouldBeNull()
        buildShelves(library + filler(1, from = 5000), now).shouldNotBeNull()
    }

    @Test
    fun `the minimum counts distinct series, not category rows`() {
        val library = listOf(entry(1, hasStarted = false)) + filler(18)
        buildShelves(library + library, now).shouldBeNull()
    }

    @Test
    fun `hidden when every shelf is empty`() {
        buildShelves(filler(40), now).shouldBeNull()
    }

    @Test
    fun `continue reading takes started series with unread chapters, latest read first, capped at 10`() {
        val reading = (1L..12L).map { entry(it, unreadCount = 2, lastRead = now - it * day) }
        val library = listOf(
            entry(100, unreadCount = 3, lastRead = null),
            entry(101, hasStarted = false, unreadCount = 5, lastRead = now),
            entry(102, unreadCount = 0, lastRead = now),
        ) + reading.shuffled(kotlin.random.Random(1)) + filler(10)

        val shelves = buildShelves(library, now).shouldNotBeNull()
        shelves.continueReading shouldContainExactly (1L..10L).toList()
    }

    @Test
    fun `new chapters takes unread chapters fetched within 7 days, newest first, capped at 15`() {
        val fresh = (1L..17L).map { entry(it, hasStarted = false, unreadCount = 1, chapterFetchedAt = now - it * 1000) }
        val library = fresh + listOf(
            entry(50, hasStarted = false, unreadCount = 1, chapterFetchedAt = now - 7 * day),
            entry(51, hasStarted = false, unreadCount = 1, chapterFetchedAt = now - 7 * day + 1),
            entry(52, hasStarted = false, unreadCount = 0, chapterFetchedAt = now),
            entry(53, hasStarted = false, unreadCount = 1, chapterFetchedAt = null),
        )

        val shelves = buildShelves(library, now).shouldNotBeNull()
        shelves.newChapters.ids shouldContainExactly (1L..15L).toList()
        shelves.newChapters.total shouldBe 18
    }

    @Test
    fun `not started takes unread series, latest added first, unknown dates last, capped at 15`() {
        val library = listOf(
            entry(1, hasStarted = false, favoriteAt = null),
            entry(2, hasStarted = false, favoriteAt = now - 2 * day),
            entry(3, hasStarted = false, favoriteAt = now - day),
            entry(4, hasStarted = false, favoriteAt = now - 2 * day),
        ) + filler(20)

        val shelves = buildShelves(library, now).shouldNotBeNull()
        shelves.notStarted.ids shouldContainExactly listOf(3L, 2L, 4L, 1L)
        shelves.notStarted.total shouldBe 4
        shelves.continueReading.shouldBeEmpty()
        shelves.newChapters.ids.shouldBeEmpty()
    }

    @Test
    fun `not started is capped at 15 and counts every candidate`() {
        val library = (1L..30L).map { entry(it, hasStarted = false, favoriteAt = now - it) }
        val shelves = buildShelves(library, now).shouldNotBeNull()
        shelves.notStarted.ids shouldContainExactly (1L..15L).toList()
        shelves.notStarted.total shouldBe 30
    }

    @Test
    fun `a series shown on one shelf is skipped by the shelves below it`() {
        val library = listOf(
            // Continue reading and new chapters both match: stays on the first
            entry(1, unreadCount = 2, lastRead = now - day, chapterFetchedAt = now - day),
            // New chapters and not started both match: stays on new chapters
            entry(2, hasStarted = false, unreadCount = 4, chapterFetchedAt = now - day, favoriteAt = now),
            entry(3, hasStarted = false, favoriteAt = now - day),
        ) + filler(20)

        val shelves = buildShelves(library, now).shouldNotBeNull()
        shelves.continueReading shouldContainExactly listOf(1L)
        shelves.newChapters.ids shouldContainExactly listOf(2L)
        shelves.newChapters.total shouldBe 1
        shelves.notStarted.ids shouldContainExactly listOf(3L)
        shelves.notStarted.total shouldBe 1
    }

    @Test
    fun `only shown entries are skipped below, not those cut by a cap`() {
        // 11 match continue reading; the 11th isn't shown there, so new chapters may show it
        val library = (1L..11L).map {
            entry(it, unreadCount = 1, lastRead = now - it * day, chapterFetchedAt = now - it * 1000)
        } + filler(20)

        val shelves = buildShelves(library, now).shouldNotBeNull()
        shelves.continueReading shouldContainExactly (1L..10L).toList()
        shelves.newChapters.ids shouldContainExactly listOf(11L)
    }

    @Test
    fun `a series in several categories appears once`() {
        val inTwo = entry(1, hasStarted = false, favoriteAt = now)
        val library = listOf(inTwo, entry(2, hasStarted = false, favoriteAt = now - day)) + filler(20) + inTwo

        val shelves = buildShelves(library, now).shouldNotBeNull()
        shelves.notStarted.ids shouldContainExactly listOf(1L, 2L)
        shelves.notStarted.total shouldBe 2
    }
}
