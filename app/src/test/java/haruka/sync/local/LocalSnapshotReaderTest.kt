package haruka.sync.local

import eu.kanade.tachiyomi.source.model.UpdateStrategy
import haruka.chapter.dedup.DedupSettings
import haruka.chapter.dedup.MangaDedupOverride
import haruka.sync.engine.LocalCategoryRow
import haruka.sync.model.BUCKET_COUNT
import haruka.sync.model.ChapterRecord
import haruka.sync.model.DedupOverrideRecord
import haruka.sync.model.EntryKey
import haruka.sync.model.EntryRecord
import haruka.sync.model.PrefRecord
import haruka.sync.model.PrefsPayload
import haruka.sync.model.ScanlatorRanking
import haruka.sync.model.TrackRecord
import haruka.sync.model.bucketOf
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.InMemoryPreferenceStore.InMemoryPreference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.history.model.History
import tachiyomi.domain.history.repository.HistoryRepository
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.track.model.Track
import tachiyomi.domain.track.repository.TrackRepository
import kotlin.time.Instant

class LocalSnapshotReaderTest {

    private val reading = Category(id = 1, name = "Reading", order = 1, flags = 0)
    private val done = Category(id = 2, name = "Done", order = 2, flags = 4)
    private val uuids = mapOf(1L to "u-reading", 2L to "u-done")

    private val mangaA = Manga.create().copy(
        id = 10,
        source = 1,
        url = "/a",
        title = "Alpha",
        author = "Author",
        genre = listOf("Drama", "Action"),
        status = 2,
        favoriteAt = Instant.fromEpochMilliseconds(1_000),
        viewerFlags = 3,
        chapterFlags = Manga.CHAPTER_SORTING_NUMBER,
        notes = "note",
        initialized = true,
        updateStrategy = UpdateStrategy.ONLY_FETCH_ONCE,
        memo = JsonObject(mapOf("k" to JsonPrimitive(1))),
    )

    // Added to the library before Mihon recorded the time: favorite, but favoriteAt unknown.
    private val mangaB = Manga.create().copy(id = 11, source = 1, url = "/b", favoriteAt = Manga.UNKNOWN_FAVORITE_AT)

    // Unfavorited since the last sync; still read because the base knows it.
    private val mangaC = Manga.create().copy(id = 12, source = 2, url = "/c", favoriteAt = null)

    private val keyA = EntryKey(1, "/a")
    private val keyB = EntryKey(1, "/b")
    private val keyC = EntryKey(2, "/c")
    private val keyGone = EntryKey(2, "/gone")

    private val chapters = mapOf(
        10L to listOf(
            chapter(id = 101, mangaId = 10, url = "/a/2", sourceOrder = 0, read = true, lastPageRead = 5),
            chapter(id = 100, mangaId = 10, url = "/a/1", sourceOrder = 1, bookmark = true),
        ),
        12L to listOf(chapter(id = 120, mangaId = 12, url = "/c/1", sourceOrder = 0)),
    )
    private val history = mapOf(
        10L to listOf(
            History(id = 1, chapterId = 101, readAt = Instant.fromEpochMilliseconds(5_000), readDuration = 60),
            // Cleared history keeps its row with a null read time; the duration must not survive.
            History(id = 2, chapterId = 100, readAt = null, readDuration = 30),
        ),
    )
    private val tracks = mapOf(10L to listOf(track(mangaId = 10, trackerId = 2), track(mangaId = 10, trackerId = 1)))
    private val categoriesByManga = mapOf(10L to listOf(done, reading), 11L to listOf(reading))
    private val excluded = mapOf(10L to listOf("Zeta", "Beta"))
    private val overrides = mapOf(
        10L to MangaDedupOverride(groupChapters = false, rankedScanlators = listOf("Best", "Worse")),
        // All-null means "follow the global settings", the same as no override.
        11L to MangaDedupOverride(),
        999L to MangaDedupOverride(mergeSplitChapters = true),
    )

    private val mangaRepository = mockk<MangaRepository>()
    private val chapterRepository = mockk<ChapterRepository>()
    private val historyRepository = mockk<HistoryRepository>()
    private val trackRepository = mockk<TrackRepository>()
    private val categoryRepository = mockk<CategoryRepository>()
    private val dedupSettings = mockk<DedupSettings>()

    private fun reader(preferenceStore: PreferenceStore = InMemoryPreferenceStore()) = LocalSnapshotReader(
        mangaRepository,
        chapterRepository,
        historyRepository,
        trackRepository,
        categoryRepository,
        dedupSettings,
        preferenceStore,
    )

    private fun stubLibrary(favorites: List<Manga>, others: List<Manga> = emptyList()) {
        coEvery { mangaRepository.getFavorites() } returns favorites
        coEvery { mangaRepository.getMangaByUrlAndSourceId(any(), any()) } answers {
            (favorites + others).firstOrNull { it.url == firstArg<String>() && it.source == secondArg<Long>() }
        }
        coEvery { chapterRepository.getChaptersByMangaIds(any()) } answers { chapters.slice(firstArg()) }
        coEvery { historyRepository.getHistoryByMangaIds(any()) } answers { history.slice(firstArg()) }
        coEvery { trackRepository.getTracksByMangaIds(any()) } answers { tracks.slice(firstArg()) }
        coEvery { categoryRepository.getCategoriesByMangaIds(any()) } answers { categoriesByManga.slice(firstArg()) }
        coEvery { mangaRepository.getExcludedScanlatorsByMangaIds(any()) } answers { excluded.slice(firstArg()) }
        every { dedupSettings.allOverrides() } returns overrides
    }

    /** One pass the way the engine reads: every bucket in ascending order with its share of [knownKeys]. */
    private fun readAll(
        reader: LocalSnapshotReader,
        knownKeys: Set<EntryKey>,
        categoryUuids: Map<Long, String>,
    ): List<List<EntryRecord>> = runBlocking {
        (0..<BUCKET_COUNT).map { index ->
            reader.readBucket(index, knownKeys.filterTo(HashSet()) { bucketOf(it) == index }, categoryUuids)
        }
    }

    @Test
    fun `entries carry the observed library state with categories, overrides and history`() {
        stubLibrary(favorites = listOf(mangaA, mangaB), others = listOf(mangaC))

        val buckets = readAll(reader(), setOf(keyA, keyC, keyGone), uuids)

        for ((index, entries) in buckets.withIndex()) {
            for (entry in entries) bucketOf(entry.key) shouldBe index
        }
        buckets.flatten() shouldContainExactlyInAnyOrder listOf(
            EntryRecord(
                key = keyA,
                favorite = true,
                favoriteAtMillis = 1_000,
                title = "Alpha",
                author = "Author",
                genre = listOf("Drama", "Action"),
                status = 2,
                initialized = true,
                updateStrategy = UpdateStrategy.ONLY_FETCH_ONCE.ordinal,
                memoJson = """{"k":1}""",
                viewerFlags = 3,
                chapterFlags = Manga.CHAPTER_SORTING_NUMBER,
                notes = "note",
                categoryUuids = listOf("u-done", "u-reading"),
                excludedScanlators = listOf("Beta", "Zeta"),
                dedupOverride = DedupOverrideRecord(
                    groupChapters = false,
                    rankedScanlators = ScanlatorRanking(listOf("Best", "Worse")),
                ),
                chapters = listOf(
                    ChapterRecord(url = "/a/1", name = "/a/1", sourceOrder = 1, bookmark = true),
                    ChapterRecord(
                        url = "/a/2",
                        name = "/a/2",
                        read = true,
                        lastPageRead = 5,
                        historyReadAtMillis = 5_000,
                        historyDurationMs = 60,
                    ),
                ),
                tracks = listOf(trackRecord(1), trackRecord(2)),
            ),
            EntryRecord(key = keyB, favorite = true, favoriteAtMillis = null, categoryUuids = listOf("u-reading")),
            EntryRecord(key = keyC, favorite = false, chapters = listOf(ChapterRecord(url = "/c/1", name = "/c/1"))),
        )
        // Known keys already among the favorites need no lookup; a row that is gone is simply absent.
        coVerify(exactly = 1) { mangaRepository.getMangaByUrlAndSourceId("/c", 2) }
        coVerify(exactly = 1) { mangaRepository.getMangaByUrlAndSourceId("/gone", 2) }
        coVerify(exactly = 0) { mangaRepository.getMangaByUrlAndSourceId("/a", 1) }
        coVerify(exactly = 1) { mangaRepository.getFavorites() }
    }

    @Test
    fun `rows are read in batches of at most 100 entries covering whole buckets`() {
        val library = (1L..250L).map {
            Manga.create().copy(id = it, source = 1, url = "/m$it", favoriteAt = Instant.fromEpochMilliseconds(it))
        }
        stubLibrary(favorites = library)
        val batches = mutableListOf<List<Long>>()
        coEvery { chapterRepository.getChaptersByMangaIds(capture(batches)) } returns emptyMap()

        val buckets = readAll(reader(), emptySet(), uuids)

        buckets.sumOf { it.size } shouldBe 250
        batches.flatten() shouldContainExactlyInAnyOrder library.map { it.id }
        val bucketOfId = library.associate { it.id to bucketOf(EntryKey(it.source, it.url)) }
        val bucketSets = batches.map { batch ->
            batch.size shouldBeLessThanOrEqual 100
            batch.map { bucketOfId.getValue(it) }.toSet()
        }
        // A bucket is never split across batches, so each returned bucket comes from one batch.
        bucketSets.flatten().size shouldBe bucketSets.flatten().toSet().size
        coVerify(exactly = 0) { mangaRepository.getMangaByUrlAndSourceId(any(), any()) }
    }

    @Test
    fun `entries in an unmapped category fail instead of dropping the membership`() {
        stubLibrary(favorites = listOf(mangaB))

        shouldThrow<IllegalStateException> { readAll(reader(), emptySet(), mapOf(2L to "u-done")) }
    }

    @Test
    fun `a current read maps unmapped categories to a placeholder and keeps the snapshot`() {
        stubLibrary(favorites = listOf(mangaB))
        val reader = reader()
        readAll(reader, emptySet(), uuids)

        val current = runBlocking { reader.readCurrent(listOf(keyB, keyGone), mapOf(2L to "u-done")) }

        current.keys shouldContainExactly setOf(keyB)
        current.getValue(keyB).record.categoryUuids shouldContainExactly listOf("${UNMAPPED_CATEGORY_PREFIX}1")
        val observed = reader.observed(keyB)!!
        val asRead = EntryRecord(keyB, favorite = true, categoryUuids = listOf("u-reading"))
        observed.matches(asRead, liveUuids = setOf("u-reading")) shouldBe true
        observed.matches(current.getValue(keyB).record, liveUuids = setOf("u-reading")) shouldBe false
        // A membership in a category the apply deleted is expected to be gone; other state still has to match.
        observed.matches(EntryRecord(keyB, favorite = true), liveUuids = emptySet()) shouldBe true
        observed.matches(EntryRecord(keyB, favorite = false), liveUuids = emptySet()) shouldBe false
    }

    @Test
    fun `categories skip the system category and are remembered`() {
        val system = Category(id = Category.UNCATEGORIZED_ID, name = "", order = 0, flags = 0)
        coEvery { categoryRepository.getAll() } returns listOf(system, reading, done)
        val reader = reader()

        val categories = runBlocking { reader.readCategories() }

        categories shouldContainExactly listOf(
            LocalCategoryRow(id = 1, name = "Reading", order = 1, flags = 0),
            LocalCategoryRow(id = 2, name = "Done", order = 2, flags = 4),
        )
        reader.observedCategories shouldBe categories
    }

    @Test
    fun `prefs hold the set whitelisted keys in stored string form`() {
        val store = InMemoryPreferenceStore(
            sequenceOf(
                InMemoryPreference("haruka_panorama_covers", false, true),
                InMemoryPreference("haruka_animation_profile", "REDUCED", "EXPRESSIVE"),
                InMemoryPreference("haruka_dedup_ranking", """["A","B"]""", ""),
                InMemoryPreference("haruka_beta_updates", true, false),
            ),
        )
        val reader = reader(store)

        val prefs = runBlocking { reader.readPrefs() }

        prefs shouldBe PrefsPayload(
            listOf(
                PrefRecord("haruka_animation_profile", "REDUCED"),
                PrefRecord("haruka_dedup_ranking", """["A","B"]"""),
                PrefRecord("haruka_panorama_covers", "false"),
            ),
        )
        reader.observedPrefs shouldBe prefs
    }

    @Test
    fun `every synced pref key has a known storage type`() {
        runBlocking { reader().readPrefs() } shouldBe PrefsPayload()
    }

    private fun <V> Map<Long, V>.slice(ids: List<Long>): Map<Long, V> = filterKeys { it in ids }

    private fun chapter(
        id: Long,
        mangaId: Long,
        url: String,
        sourceOrder: Long,
        read: Boolean = false,
        bookmark: Boolean = false,
        lastPageRead: Int = 0,
    ) = Chapter.create().copy(
        id = id,
        mangaId = mangaId,
        url = url,
        name = url,
        sourceOrder = sourceOrder,
        chapterNumber = 0.0,
        read = read,
        bookmark = bookmark,
        lastPageRead = lastPageRead,
    )

    private fun track(mangaId: Long, trackerId: Long) = Track(
        id = trackerId,
        mangaId = mangaId,
        trackerId = trackerId,
        remoteId = 100 + trackerId,
        libraryId = null,
        title = "T$trackerId",
        lastChapterRead = 4.5,
        totalChapters = 10,
        status = 1,
        score = 7.0,
        remoteUrl = "https://t/$trackerId",
        startDate = Instant.fromEpochMilliseconds(2_000),
        finishDate = null,
        private = trackerId == 2L,
    )

    private fun trackRecord(trackerId: Long) = TrackRecord(
        trackerId = trackerId,
        remoteId = 100 + trackerId,
        title = "T$trackerId",
        lastChapterRead = 4.5,
        totalChapters = 10,
        status = 1,
        score = 7.0,
        remoteUrl = "https://t/$trackerId",
        startDateMillis = 2_000,
        private = trackerId == 2L,
    )
}
