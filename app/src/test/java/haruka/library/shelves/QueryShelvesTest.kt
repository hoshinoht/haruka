package haruka.library.shelves

import eu.kanade.tachiyomi.ui.library.LibraryItem
import haruka.library.CustomShelf
import haruka.library.LibraryGroupBy
import haruka.library.NamedShelf
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.track.model.Track

class QueryShelvesTest {

    private val labels = GroupLabels(
        statuses = mapOf(1L to "Ongoing", 2L to "Completed"),
        unknownStatus = "Unknown",
        trackers = mapOf(1L to "MyAnimeList", 2L to "AniList"),
        notTracked = "Not tracked",
    )

    private fun item(
        id: Long,
        source: Long = 1,
        unread: Long = 0,
        genre: List<String>? = null,
        author: String? = null,
        status: Long = 1,
    ): LibraryItem {
        val manga = Manga.create().copy(
            id = id,
            source = source,
            title = "Series $id",
            author = author,
            genre = genre,
            status = status,
        )
        val total = 20L
        return LibraryItem(
            libraryManga = LibraryManga(
                manga = manga,
                categories = listOf(0),
                totalChapters = total,
                readCount = total - unread,
                bookmarkCount = 0,
                latestUpload = null,
                chapterFetchedAt = null,
                lastRead = null,
            ),
            downloadCount = 0,
            unreadCount = unread,
            isLocal = false,
            sourceName = if (source == 1L) "mangadex" else "comick",
            sourceLanguage = "en",
            badges = LibraryItem.Badges(0, 0, false, ""),
        )
    }

    private fun track(mangaId: Long, trackerId: Long) = Track(
        id = mangaId * 10 + trackerId,
        mangaId = mangaId,
        trackerId = trackerId,
        remoteId = 0,
        libraryId = null,
        title = "",
        lastChapterRead = 0.0,
        totalChapters = 0,
        status = 0,
        score = 0.0,
        remoteUrl = "",
        startDate = null,
        finishDate = null,
        private = false,
    )

    private fun build(
        items: List<LibraryItem>,
        custom: List<CustomShelf> = emptyList(),
        groupBy: LibraryGroupBy = LibraryGroupBy.NONE,
        tracks: Map<Long, List<Track>> = emptyMap(),
        sourceNames: Map<Long, String> = mapOf(1L to "MangaDex", 2L to "Comick"),
    ): List<NamedShelf> = buildNamedShelves(items, custom, groupBy, labels, tracks, sourceNames, naturalOrder())

    private fun List<NamedShelf>.summary() = map { it.name to it.ids }

    @Test
    fun `a custom shelf holds every series its query matches, in library order`() {
        val items = listOf(
            item(1, unread = 3, genre = listOf("Romance")),
            item(2, source = 2, unread = 0, genre = listOf("Action")),
            item(3, unread = 1, genre = listOf("Action")),
            item(4, source = 2, unread = 5),
        )
        val shelves = build(
            items,
            custom = listOf(
                CustomShelf("u", "Unread", "unread>0"),
                CustomShelf("s", "MangaDex", "source:mangadex"),
                CustomShelf("n", "No romance", "-genre:romance"),
                CustomShelf("c", "Comick unread", "source:comick unread>0"),
            ),
        )
        shelves.summary() shouldContainExactly listOf(
            "Unread" to listOf(1L, 3L, 4L),
            "MangaDex" to listOf(1L, 3L),
            "No romance" to listOf(2L, 3L, 4L),
            "Comick unread" to listOf(4L),
        )
        shelves.first().key shouldBe "custom:u"
    }

    @Test
    fun `custom shelves with no members or an invalid query are hidden`() {
        val shelves = build(
            listOf(item(1, unread = 2), item(2)),
            custom = listOf(
                CustomShelf("a", "Huge", "total>1000"),
                CustomShelf("b", "Broken", "(unread>0"),
                CustomShelf("c", "Unread", "unread>0"),
            ),
        )
        shelves.summary() shouldContainExactly listOf("Unread" to listOf(1L))
    }

    @Test
    fun `a query is parsed once and the tree reused`() {
        val first = ShelfQueries.parse("unread>0 -genre:romance")
        first.shouldBeInstanceOf<ShelfQuery.Valid>()
        ShelfQueries.parse("unread>0 -genre:romance") shouldBeSameInstanceAs first
    }

    @Test
    fun `queries the parser would silently get wrong are invalid`() {
        fun error(query: String) = (ShelfQueries.parse(query) as? ShelfQuery.Invalid)?.error

        error("") shouldBe QueryError.Blank
        error("   ") shouldBe QueryError.Blank
        error("&&") shouldBe QueryError.Blank
        error("(unread>0 || total>5") shouldBe QueryError.Parentheses
        error("unread>0)") shouldBe QueryError.Parentheses
        error(")(") shouldBe QueryError.Parentheses
        error("unread>x") shouldBe QueryError.NotANumber("unread>x")
        error("fi>=soon") shouldBe QueryError.NotANumber("fetchinterval>=soon")
        error("source:mangadex added>yesterday") shouldBe QueryError.NotADate("added>yesterday")
        error("(unread>0 || total>5) added>=2026-01-31") shouldBe null
        error("romance") shouldBe null
    }

    @Test
    fun `source groups run largest first, then by name`() {
        val items = listOf(item(1, source = 2), item(2, source = 1), item(3, source = 3), item(4, source = 2))
        build(items, groupBy = LibraryGroupBy.SOURCE, sourceNames = mapOf(1L to "B", 2L to "Z", 3L to "A"))
            .summary() shouldContainExactly listOf("Z" to listOf(1L, 4L), "A" to listOf(3L), "B" to listOf(2L))
    }

    @Test
    fun `generated shelves follow the custom ones`() {
        val shelves = build(
            listOf(item(1, unread = 1), item(2)),
            custom = listOf(CustomShelf("u", "Unread", "unread>0")),
            groupBy = LibraryGroupBy.STATUS,
        )
        shelves.map { it.key } shouldContainExactly listOf("custom:u", "group:STATUS:1")
    }

    @Test
    fun `statuses without a name share one unknown shelf`() {
        val items = listOf(item(1, status = 2), item(2, status = 0), item(3, status = 99), item(4, status = 2))
        build(items, groupBy = LibraryGroupBy.STATUS).summary() shouldContainExactly listOf(
            "Completed" to listOf(1L, 4L),
            "Unknown" to listOf(2L, 3L),
        )
    }

    @Test
    fun `a series sits on every tracker it is tracked with, and untracked ones on Not tracked`() {
        val items = listOf(item(1), item(2), item(3), item(4))
        val tracks = mapOf(
            1L to listOf(track(1, 1), track(1, 2)),
            2L to listOf(track(2, 2)),
            // A tracker that no longer exists doesn't count as tracked
            3L to listOf(track(3, 77)),
        )
        build(items, groupBy = LibraryGroupBy.TRACKER, tracks = tracks).summary() shouldContainExactly listOf(
            "AniList" to listOf(1L, 2L),
            "Not tracked" to listOf(3L, 4L),
            "MyAnimeList" to listOf(1L),
        )
    }

    @Test
    fun `an author needs 2 series, matched ignoring case, across co-authored series`() {
        val items = listOf(
            item(1, author = "ONE, Murata Yusuke"),
            item(2, author = "One"),
            item(3, author = "Murata Yusuke"),
            // Named twice on one series: still one series
            item(4, author = "Solo, Solo"),
            item(5, author = null),
            item(6, author = " "),
        )
        build(items, groupBy = LibraryGroupBy.AUTHOR).summary() shouldContainExactly listOf(
            "Murata Yusuke" to listOf(1L, 3L),
            "ONE" to listOf(1L, 2L),
        )
    }

    @Test
    fun `no grouping and no custom shelves gives nothing`() {
        build(listOf(item(1), item(2))).shouldBeEmpty()
    }
}
