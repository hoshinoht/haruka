package haruka.library

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
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
        sourceUnavailable: Boolean = false,
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
        sourceUnavailable = sourceUnavailable,
    )

    // Started and caught up: qualifies for no shelf, only pads the library past the minimum.
    private fun filler(count: Int, from: Long = 1000) = (0 until count).map { entry(from + it) }

    private fun List<Shelf>.of(kind: ShelfKind) = firstOrNull { it.kind == kind }
    private fun List<Shelf>.ids(kind: ShelfKind) = of(kind)?.ids.orEmpty()

    @Test
    fun `hidden below 20 series`() {
        val library = listOf(entry(1, hasStarted = false)) + filler(18)
        buildShelves(library, now).shouldBeEmpty()
        buildShelves(library + filler(1, from = 5000), now).map { it.kind } shouldContainExactly
            listOf(ShelfKind.NotStarted)
    }

    @Test
    fun `the minimum counts distinct series, not category rows`() {
        val library = listOf(entry(1, hasStarted = false)) + filler(18)
        buildShelves(library + library, now).shouldBeEmpty()
    }

    @Test
    fun `hidden when every shelf is empty`() {
        buildShelves(filler(40), now).shouldBeEmpty()
    }

    @Test
    fun `shelves run in a fixed order and empty ones are left out`() {
        val library = listOf(
            entry(1, sourceUnavailable = true),
            entry(2, unreadCount = 1, lastRead = now),
        ) + filler(20)
        buildShelves(library, now).map { it.kind } shouldContainExactly
            listOf(ShelfKind.ContinueReading, ShelfKind.NeedsNewSource)
    }

    @Test
    fun `continue reading takes started series with unread chapters, latest read first, capped at 10`() {
        val reading = (1L..12L).map { entry(it, unreadCount = 2, lastRead = now - it * day) }
        val library = listOf(
            entry(100, unreadCount = 3, lastRead = null),
            entry(101, hasStarted = false, unreadCount = 5, lastRead = now),
            entry(102, unreadCount = 0, lastRead = now),
        ) + reading.shuffled(kotlin.random.Random(1)) + filler(10)

        val shelf = buildShelves(library, now).of(ShelfKind.ContinueReading)!!
        shelf.ids shouldContainExactly (1L..10L).toList()
        shelf.total shouldBe 13
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

        val shelf = buildShelves(library, now).of(ShelfKind.NewChapters)!!
        shelf.ids shouldContainExactly (1L..15L).toList()
        shelf.total shouldBe 18
    }

    @Test
    fun `not started takes unread series, latest added first, unknown dates last`() {
        val library = listOf(
            entry(1, hasStarted = false, favoriteAt = null),
            entry(2, hasStarted = false, favoriteAt = now - 2 * day),
            entry(3, hasStarted = false, favoriteAt = now - day),
            entry(4, hasStarted = false, favoriteAt = now - 2 * day),
        ) + filler(20)

        val shelves = buildShelves(library, now)
        shelves.ids(ShelfKind.NotStarted) shouldContainExactly listOf(3L, 2L, 4L, 1L)
        shelves.of(ShelfKind.NotStarted)!!.total shouldBe 4
        shelves.map { it.kind } shouldContainExactly listOf(ShelfKind.NotStarted)
    }

    @Test
    fun `not started is capped at 15 and counts every candidate`() {
        val library = (1L..30L).map { entry(it, hasStarted = false, favoriteAt = now - it) }
        val shelf = buildShelves(library, now).of(ShelfKind.NotStarted)!!
        shelf.ids shouldContainExactly (1L..15L).toList()
        shelf.total shouldBe 30
    }

    @Test
    fun `needs a new source takes unavailable sources in library order, capped at 15 with every one counted`() {
        val stale = (1L..20L).map { entry(it, sourceUnavailable = true) }.shuffled(kotlin.random.Random(2))
        val library = stale + filler(5)

        val shelf = buildShelves(library, now).of(ShelfKind.NeedsNewSource)!!
        shelf.ids shouldContainExactly stale.take(15).map { it.id }
        shelf.total shouldBe 20
    }

    @Test
    fun `a series shown on one shelf is skipped by the shelves below it`() {
        val library = listOf(
            // Continue reading and new chapters both match: stays on the first
            entry(1, unreadCount = 2, lastRead = now - day, chapterFetchedAt = now - day),
            // New chapters and not started both match: stays on new chapters
            entry(2, hasStarted = false, unreadCount = 4, chapterFetchedAt = now - day, favoriteAt = now),
            // Not started and needs a new source both match: stays on not started
            entry(3, hasStarted = false, favoriteAt = now - day, sourceUnavailable = true),
            entry(4, sourceUnavailable = true),
        ) + filler(20)

        val shelves = buildShelves(library, now)
        shelves.ids(ShelfKind.ContinueReading) shouldContainExactly listOf(1L)
        shelves.ids(ShelfKind.NewChapters) shouldContainExactly listOf(2L)
        shelves.of(ShelfKind.NewChapters)!!.total shouldBe 1
        shelves.ids(ShelfKind.NotStarted) shouldContainExactly listOf(3L)
        shelves.ids(ShelfKind.NeedsNewSource) shouldContainExactly listOf(4L)
    }

    @Test
    fun `only shown entries are skipped below, not those cut by a cap`() {
        // 11 match continue reading; the 11th isn't shown there, so new chapters may show it
        val library = (1L..11L).map {
            entry(it, unreadCount = 1, lastRead = now - it * day, chapterFetchedAt = now - it * 1000)
        } + filler(20)

        val shelves = buildShelves(library, now)
        shelves.ids(ShelfKind.ContinueReading) shouldContainExactly (1L..10L).toList()
        shelves.ids(ShelfKind.NewChapters) shouldContainExactly listOf(11L)
    }

    @Test
    fun `a shelf turned off frees its series for the shelves below`() {
        val library = listOf(
            entry(1, unreadCount = 2, lastRead = now - day, chapterFetchedAt = now - day),
            entry(2, hasStarted = false, unreadCount = 4, chapterFetchedAt = now - day, favoriteAt = now),
            entry(3, hasStarted = false, favoriteAt = now - day, sourceUnavailable = true),
        ) + filler(20)

        val withoutTop = buildShelves(library, now, ShelfKind.entries.toSet() - ShelfKind.ContinueReading)
        withoutTop.map { it.kind } shouldContainExactly listOf(ShelfKind.NewChapters, ShelfKind.NotStarted)
        withoutTop.ids(ShelfKind.NewChapters) shouldContainExactly listOf(1L, 2L)

        val withoutMiddle = buildShelves(
            library,
            now,
            setOf(ShelfKind.ContinueReading, ShelfKind.NeedsNewSource),
        )
        withoutMiddle.ids(ShelfKind.ContinueReading) shouldContainExactly listOf(1L)
        withoutMiddle.ids(ShelfKind.NeedsNewSource) shouldContainExactly listOf(3L)

        buildShelves(library, now, emptySet()).shouldBeEmpty()
    }

    @Test
    fun `a series in several categories appears once`() {
        val inTwo = entry(1, hasStarted = false, favoriteAt = now)
        val library = listOf(inTwo, entry(2, hasStarted = false, favoriteAt = now - day)) + filler(20) + inTwo

        val shelf = buildShelves(library, now).of(ShelfKind.NotStarted)!!
        shelf.ids shouldContainExactly listOf(1L, 2L)
        shelf.total shouldBe 2
    }

    @Test
    fun `see all keeps every member in list order, without the cap or the shelves above`() {
        // Sorted by the user's sort: ids descending here
        val list = (40L downTo 1L).map {
            entry(
                it,
                hasStarted = it > 20,
                unreadCount = if (it % 2 == 0L) 1 else 0,
                lastRead = now - it,
                chapterFetchedAt = now - day,
                sourceUnavailable = it % 5 == 0L,
            )
        }

        filterToShelf(list, ShelfKind.NotStarted, now) { it }.map { it.id } shouldContainExactly
            (20L downTo 1L).toList()
        // Also in Continue reading (started, unread), yet still listed under New chapters
        filterToShelf(list, ShelfKind.NewChapters, now) { it }.map { it.id } shouldContainExactly
            (40L downTo 1L).filter { it % 2 == 0L }
        filterToShelf(list, ShelfKind.NeedsNewSource, now) { it }.map { it.id } shouldContainExactly
            listOf(40L, 35L, 30L, 25L, 20L, 15L, 10L, 5L)
        filterToShelf(list, ShelfKind.ContinueReading, now) { it }.map { it.id } shouldContainExactly
            (40L downTo 21L).filter { it % 2 == 0L }
    }

    @Test
    fun `inflated counts need 1_5x the chapters and at least 10 extra uploads`() {
        isCountInflated(uploads = 712, chapters = 101, hasOutlierNumber = false) shouldBe true
        isCountInflated(uploads = 150, chapters = 100, hasOutlierNumber = false) shouldBe true
        isCountInflated(uploads = 149, chapters = 100, hasOutlierNumber = false) shouldBe false
        isCountInflated(uploads = 9, chapters = 3, hasOutlierNumber = false) shouldBe false
        isCountInflated(uploads = 13, chapters = 3, hasOutlierNumber = false) shouldBe true
        isCountInflated(uploads = 0, chapters = 0, hasOutlierNumber = false) shouldBe false
    }

    @Test
    fun `a bogus chapter number alone makes the count inflated`() {
        isCountInflated(uploads = 142, chapters = 140, hasOutlierNumber = true) shouldBe true
    }

    @Test
    fun `the inflated shelf comes last and takes only what the shelves above left`() {
        val library = (1L..25L).map { entry(it, unreadCount = 3, lastRead = it) } +
            ShelfInput(
                id = 99, title = "Big", lastRead = null, lastUpdate = null, latestUpload = null,
                chapterFetchedAt = null, favoriteAt = null, unreadCount = 0, totalChapters = 700,
                hasStarted = true, countsInflated = true,
            )
        val shelves = buildShelves(library, now)
        shelves.last().kind shouldBe ShelfKind.InflatedCounts
        shelves.last().ids shouldContainExactly listOf(99L)
    }
}
