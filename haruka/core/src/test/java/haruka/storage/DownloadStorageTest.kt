package haruka.storage

import haruka.chapter.dedup.chapter
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class DownloadStorageTest {

    private fun series(name: String, size: Long = 10) = SeriesFolder(name, size, emptyList())

    private fun statuses(
        folders: List<SourceFolder>,
        sources: List<KnownSource>,
        manga: List<KnownManga>,
    ): Map<String, Pair<SeriesStatus, Long?>> =
        classifyFolders(folders, DownloadMatcher(sources, manga))
            .associate { "${it.sourceFolder}/${it.folder.name}" to (it.status to it.owner?.id) }

    @Test
    fun `a source folder shared by several sources matches manga of each, ignoring case`() {
        val folders = listOf(SourceFolder("mangadex (en)", listOf(series("A"), series("B"))))
        val sources = listOf(KnownSource(1, "MangaDex (EN)"), KnownSource(2, "MangaDex (EN)"))
        val manga = listOf(KnownManga(10, 1, "A", favorite = true), KnownManga(20, 2, "B", favorite = true))

        statuses(folders, sources, manga) shouldBe mapOf(
            "mangadex (en)/A" to (SeriesStatus.IN_LIBRARY to 10L),
            "mangadex (en)/B" to (SeriesStatus.IN_LIBRARY to 20L),
        )
    }

    @Test
    fun `series folder names match exactly`() {
        val folders = listOf(SourceFolder("Src", listOf(series("one piece"))))
        val result = statuses(folders, listOf(KnownSource(1, "Src")), listOf(KnownManga(10, 1, "One Piece", true)))
        result.getValue("Src/one piece") shouldBe (SeriesStatus.UNRECOGNISED to null)
    }

    @Test
    fun `a single non-library match is not in library`() {
        val folders = listOf(SourceFolder("Src", listOf(series("A"))))
        val result = statuses(folders, listOf(KnownSource(1, "Src")), listOf(KnownManga(10, 1, "A", false)))
        result.getValue("Src/A") shouldBe (SeriesStatus.NOT_IN_LIBRARY to 10L)
    }

    @Test
    fun `several non-library matches across shared sources are ambiguous`() {
        val folders = listOf(SourceFolder("Src", listOf(series("A"))))
        val sources = listOf(KnownSource(1, "Src"), KnownSource(2, "src"))
        val manga = listOf(KnownManga(10, 1, "A", false), KnownManga(20, 2, "A", false))
        statuses(folders, sources, manga).getValue("Src/A") shouldBe (SeriesStatus.UNRECOGNISED to null)
    }

    @Test
    fun `two library matches are ambiguous, one library match outranks non-library ones`() {
        val folders = listOf(SourceFolder("Src", listOf(series("A"), series("B"))))
        val sources = listOf(KnownSource(1, "Src"), KnownSource(2, "Src"))
        val manga = listOf(
            KnownManga(10, 1, "A", true),
            KnownManga(20, 2, "A", true),
            KnownManga(30, 1, "B", false),
            KnownManga(40, 2, "B", true),
        )
        val result = statuses(folders, sources, manga)
        result.getValue("Src/A") shouldBe (SeriesStatus.UNRECOGNISED to null)
        result.getValue("Src/B") shouldBe (SeriesStatus.IN_LIBRARY to 40L)
    }

    @Test
    fun `a folder of a missing source is unrecognised even when a manga elsewhere has its name`() {
        val folders = listOf(SourceFolder("Gone", listOf(series("A"))))
        val result = statuses(folders, listOf(KnownSource(1, "Src")), listOf(KnownManga(10, 1, "A", true)))
        result.getValue("Gone/A") shouldBe (SeriesStatus.UNRECOGNISED to null)
    }

    @Test
    fun `only sources with a folder no library entry owns need their non-library manga`() {
        val folders = listOf(
            SourceFolder("Owned", listOf(series("A"))),
            SourceFolder("Shared", listOf(series("A"), series("Unknown"))),
            SourceFolder("Gone", listOf(series("X"))),
        )
        val sources = listOf(KnownSource(1, "Owned"), KnownSource(2, "Shared"), KnownSource(3, "shared"))
        val favorites = listOf(KnownManga(10, 1, "A", true), KnownManga(20, 2, "A", true))
        sourcesToResolve(folders, DownloadMatcher(sources, favorites)) shouldBe setOf(2L, 3L)
    }

    @Test
    fun `summary totals per source and largest series`() {
        val a = StoredSeries("S1", series("a", 5), SeriesStatus.IN_LIBRARY, KnownManga(1, 1, "a", true))
        val b = StoredSeries("S1", series("b", 50), SeriesStatus.NOT_IN_LIBRARY, KnownManga(2, 1, "b", false))
        val c = StoredSeries("S2", series("c", 30), SeriesStatus.UNRECOGNISED, null)
        val d = StoredSeries("S2", series("d", 40), SeriesStatus.NOT_IN_LIBRARY, KnownManga(3, 2, "d", false))

        val summary = summarize(listOf(a, b, c, d), largestCount = 2)

        summary.total shouldBe 125
        summary.seriesCount shouldBe 4
        summary.sources.map { it.name to it.size } shouldBe listOf("S2" to 70L, "S1" to 55L)
        summary.sources[1].series shouldBe listOf(b, a)
        summary.largest shouldBe listOf(b, d)
        summary.notInLibrary shouldBe listOf(b, d)
        summary.unrecognised shouldBe listOf(c)
    }

    @Test
    fun `downloads match the first known name ignoring case`() {
        val entries = listOf(ChapterEntry("Group_Ch 1", 3), ChapterEntry("ch 2.cbz", 7))
        val names = mapOf(1L to listOf("x", "group_ch 1"), 2L to listOf("Ch 2", "Ch 2.cbz"), 3L to listOf("Ch 3"))
        val result = matchDownloads(entries, names.keys.toList(), { it }) { names.getValue(it) }
        result shouldBe mapOf(1L to entries[0], 2L to entries[1])
    }

    // --- Read chapters ---

    private val chapters = listOf(
        chapter(1, 1.0, "A"),
        chapter(2, 1.0, "B"),
        chapter(3, 2.0, "A"),
        chapter(4, 2.0, "B"),
        chapter(5, 3.1, "A"),
        chapter(6, 3.2, "A"),
        chapter(7, 3.0, "B"),
    )

    private val cleanup = ReadCleanup(
        keepBookmarked = true,
        inExcludedCategory = false,
        readInAnotherVersion = false,
        mergeSplitChapters = true,
    )

    private fun toDelete(
        read: Set<Long>,
        downloaded: Set<Long> = chapters.map { it.id }.toSet(),
        bookmarked: Set<Long> = emptySet(),
        cleanup: ReadCleanup = this.cleanup,
    ) = readChaptersToDelete(chapters, read, bookmarked, downloaded, cleanup)

    @Test
    fun `only downloaded read chapters are selected`() {
        toDelete(read = setOf(1, 3), downloaded = setOf(1, 2, 4)) shouldBe setOf(1L)
    }

    @Test
    fun `bookmarked chapters are kept unless bookmarked deletion is on`() {
        toDelete(read = setOf(1, 3), bookmarked = setOf(3)) shouldBe setOf(1L)
        toDelete(read = setOf(1, 3), bookmarked = setOf(3), cleanup = cleanup.copy(keepBookmarked = false)) shouldBe
            setOf(1L, 3L)
    }

    @Test
    fun `a series in an excluded category keeps everything`() {
        toDelete(
            read = setOf(1, 3),
            cleanup = cleanup.copy(inExcludedCategory = true, readInAnotherVersion = true),
        ) shouldBe
            emptySet()
    }

    @Test
    fun `copies of a chapter read in another version count as read only with the option`() {
        toDelete(read = setOf(1)) shouldBe setOf(1L)
        toDelete(read = setOf(1), cleanup = cleanup.copy(readInAnotherVersion = true)) shouldBe setOf(1L, 2L)
    }

    @Test
    fun `read copies still keep bookmarks and only take downloaded chapters`() {
        val withCopies = cleanup.copy(readInAnotherVersion = true)
        toDelete(read = setOf(3), downloaded = setOf(3, 4), bookmarked = setOf(4), cleanup = withCopies) shouldBe
            setOf(3L)
        toDelete(read = setOf(3), downloaded = setOf(4), cleanup = withCopies) shouldBe setOf(4L)
    }

    @Test
    fun `a split copy reads its chapter only once every part is read`() {
        val withCopies = cleanup.copy(readInAnotherVersion = true)
        toDelete(read = setOf(5), cleanup = withCopies) shouldBe setOf(5L)
        toDelete(read = setOf(5, 6), cleanup = withCopies) shouldBe setOf(5L, 6L, 7L)
        toDelete(read = setOf(7), cleanup = withCopies) shouldBe setOf(7L, 5L, 6L)
    }

    @Test
    fun `excluded categories include the default one for uncategorised series`() {
        inExcludedCategory(emptyList(), setOf(0L)) shouldBe true
        inExcludedCategory(emptyList(), setOf(5L)) shouldBe false
        inExcludedCategory(listOf(4L, 5L), setOf(5L)) shouldBe true
        inExcludedCategory(listOf(4L), setOf(0L)) shouldBe false
    }
}
