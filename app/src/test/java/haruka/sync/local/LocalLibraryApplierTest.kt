package haruka.sync.local

import eu.kanade.domain.manga.interactor.SetExcludedScanlators
import haruka.chapter.dedup.DedupSettings
import haruka.chapter.dedup.MangaDedupOverride
import haruka.sync.engine.CategoryApplyResult
import haruka.sync.merge.CategoryChangeSet
import haruka.sync.merge.EntryField
import haruka.sync.merge.EntryUpdate
import haruka.sync.merge.LocalChangeSet
import haruka.sync.merge.PrefChangeSet
import haruka.sync.model.BUCKET_COUNT
import haruka.sync.model.CategoryRecord
import haruka.sync.model.ChapterRecord
import haruka.sync.model.DedupOverrideRecord
import haruka.sync.model.EntryKey
import haruka.sync.model.EntryRecord
import haruka.sync.model.PrefRecord
import haruka.sync.model.ScanlatorRanking
import haruka.sync.model.TrackRecord
import haruka.sync.model.bucketOf
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.backup.model.RestoredManga
import tachiyomi.domain.backup.repository.RestoreRepository
import tachiyomi.domain.category.interactor.DeleteCategory
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.model.NewCategory
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.history.model.History
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.history.repository.HistoryRepository
import tachiyomi.domain.manga.interactor.FetchInterval
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.track.model.Track
import tachiyomi.domain.track.repository.TrackRepository
import kotlin.time.Clock
import kotlin.time.Instant

class LocalLibraryApplierTest {

    // In-memory library behind the mocked repositories, so the guard and the re-read see the writes.
    private val mangas = ArrayList<Manga>()
    private val chapters = HashMap<Long, List<Chapter>>()
    private val history = HashMap<Long, List<History>>()
    private val tracks = HashMap<Long, List<Track>>()
    private val memberships = HashMap<Long, List<Long>>()
    private val excluded = HashMap<Long, Set<String>>()
    private val categories = ArrayList<Category>()
    private var overrides = mapOf<Long, MangaDedupOverride>()
    private var nextId = 1_000L

    private val mangaUpdates = ArrayList<List<MangaUpdate>>()
    private val chapterUpdates = ArrayList<List<ChapterUpdate>>()
    private val historyUpserts = ArrayList<HistoryUpdate>()
    private val restoreCalls = ArrayList<List<RestoredManga>>()

    private val mangaRepository = mockk<MangaRepository>()
    private val chapterRepository = mockk<ChapterRepository>()
    private val historyRepository = mockk<HistoryRepository>()
    private val trackRepository = mockk<TrackRepository>(relaxUnitFun = true)
    private val categoryRepository = mockk<CategoryRepository>()
    private val restoreRepository = mockk<RestoreRepository>()
    private val fetchInterval = mockk<FetchInterval>()
    private val setMangaCategories = mockk<SetMangaCategories>()
    private val setExcludedScanlators = mockk<SetExcludedScanlators>()
    private val deleteCategory = mockk<DeleteCategory>()
    private val dedupSettings = mockk<DedupSettings>()
    private val preferenceStore = mockk<PreferenceStore>()

    private val reader = LocalSnapshotReader(
        mangaRepository,
        chapterRepository,
        historyRepository,
        trackRepository,
        categoryRepository,
        dedupSettings,
        preferenceStore,
    )
    private val applier = LocalLibraryApplier(
        reader,
        mangaRepository,
        chapterRepository,
        historyRepository,
        trackRepository,
        categoryRepository,
        restoreRepository,
        fetchInterval,
        setMangaCategories,
        setExcludedScanlators,
        deleteCategory,
        dedupSettings,
        preferenceStore,
    )

    private val reading = Category(id = 1, name = "Reading", order = 0, flags = 0)
    private val done = Category(id = 2, name = "Done", order = 1, flags = 0)
    private val categoryIds = mapOf("u-reading" to 1L, "u-done" to 2L)

    init {
        categories += listOf(reading, done)
        stubRepositories()
    }

    @Test
    fun `new entries go through the restore path with every chapter fetched at the favorite time`() {
        val key = EntryKey(1, "/new")
        val record = EntryRecord(
            key = key,
            favorite = true,
            favoriteAtMillis = 50_000,
            title = "New",
            genre = listOf("Drama"),
            memoJson = """{"k":1}""",
            viewerFlags = 2,
            notes = "n",
            categoryUuids = listOf("u-done"),
            excludedScanlators = listOf("Bad"),
            dedupOverride = DedupOverrideRecord(
                groupChapters = false,
                rankedScanlators = ScanlatorRanking(listOf("A")),
            ),
            chapters = listOf(
                ChapterRecord(url = "/new/1", name = "One", chapterNumber = 1.0, dateUploadMillis = 10),
                ChapterRecord(url = "/new/2", read = true, historyReadAtMillis = 40_000, historyDurationMs = 90),
                ChapterRecord(url = "/new/3", bookmark = true, lastPageRead = 7),
            ),
            tracks = listOf(TrackRecord(trackerId = 3, remoteId = 9, lastChapterRead = 2.0, startDateMillis = 5)),
        )
        snapshot()

        val result = applyEntries(LocalChangeSet(inserts = listOf(record)))

        result.skipped.shouldBeEmpty()
        result.unrealized.shouldBeEmpty()
        val restored = restoreCalls.single().single()
        val favoriteAt = Instant.fromEpochMilliseconds(50_000)
        restored.manga.favoriteAt shouldBe favoriteAt
        restored.manga.viewerFlags shouldBe 2
        restored.manga.notes shouldBe "n"
        // Updates lists chapters fetched strictly after the favorite time, so none of these show up there.
        restored.chapters.map { it.url } shouldContainExactly listOf("/new/1", "/new/2", "/new/3")
        restored.chapters.map { it.dateFetch }.toSet() shouldBe setOf(favoriteAt)
        restored.chapters[1].read shouldBe true
        restored.chapters[2].lastPageRead shouldBe 7
        restored.categoryIds shouldContainExactly listOf(2L)
        restored.history.single().chapterUrl shouldBe "/new/2"
        restored.history.single().readDuration shouldBe 90
        restored.tracks.single().trackerId shouldBe 3
        restored.excludedScanlators shouldContainExactly listOf("Bad")
        val id = mangas.single { it.url == "/new" }.id
        overrides[id] shouldBe MangaDedupOverride(groupChapters = false, rankedScanlators = listOf("A"))
    }

    @Test
    fun `an entry without a known favorite time is inserted at now with its chapters fetched then`() {
        val record = EntryRecord(EntryKey(1, "/x"), favorite = true, chapters = listOf(ChapterRecord("/x/1")))
        snapshot()
        val before = Clock.System.now()

        applyEntries(LocalChangeSet(inserts = listOf(record))).unrealized.shouldBeEmpty()

        val restored = restoreCalls.single().single()
        val favoriteAt = restored.manga.favoriteAt!!
        (favoriteAt >= before.truncatedToMillis() && favoriteAt <= Clock.System.now()) shouldBe true
        restored.chapters.single().dateFetch shouldBe favoriteAt
    }

    @Test
    fun `restores run at most 25 entries per call`() {
        val records = (1..60).map { EntryRecord(EntryKey(1, "/m$it"), favorite = true, favoriteAtMillis = 1) }
        snapshot()

        applyEntries(LocalChangeSet(inserts = records))

        restoreCalls.map { it.size } shouldContainExactly listOf(25, 25, 10)
    }

    @Test
    fun `an entry that appeared locally since the snapshot is not restored`() {
        snapshot()
        val key = addManga(url = "/late", favoriteAt = 5)

        val result = applyEntries(LocalChangeSet(inserts = listOf(EntryRecord(key, favorite = true))))

        result.skipped shouldContainExactly setOf(key)
        restoreCalls.shouldBeEmpty()
    }

    @Test
    fun `existing rows take exact state through the repositories`() {
        val key = addManga(
            url = "/a",
            favoriteAt = 1_000,
            viewerFlags = 1,
            chapters = listOf(
                chapter(id = 11, url = "/a/1", read = true),
                chapter(id = 12, url = "/a/2", bookmark = true),
                chapter(id = 13, url = "/a/3", lastPageRead = 4),
            ),
            history = listOf(
                History(id = 7, chapterId = 12, readAt = Instant.fromEpochMilliseconds(3_000), readDuration = 20),
            ),
            tracks = listOf(track(trackerId = 1, lastChapterRead = 1.0), track(trackerId = 2)),
            categoryIds = listOf(1),
            excluded = setOf("X"),
        )
        val id = mangas.single().id
        snapshot()
        val update = EntryUpdate(
            entry = EntryRecord(
                key = key,
                favorite = true,
                favoriteAtMillis = 1_000,
                viewerFlags = 5,
                notes = "merged",
                dedupOverride = DedupOverrideRecord(mergeSplitChapters = true),
            ),
            fields = setOf(
                EntryField.VIEWER_FLAGS,
                EntryField.NOTES,
                EntryField.CATEGORIES,
                EntryField.EXCLUDED_SCANLATORS,
                EntryField.DEDUP_OVERRIDE,
            ),
            chapters = listOf(
                ChapterRecord(url = "/a/1"),
                ChapterRecord(url = "/a/2"),
                ChapterRecord(url = "/a/3", read = true, lastPageRead = 4),
            ),
            upsertTracks = listOf(TrackRecord(trackerId = 1, lastChapterRead = 3.0)),
            deleteTrackerIds = listOf(2),
        )

        val result = applyEntries(LocalChangeSet(updates = listOf(update)))

        result.skipped.shouldBeEmpty()
        result.unrealized.shouldBeEmpty()
        val mangaUpdate = mangaUpdates.single().single()
        mangaUpdate.isSet(MangaUpdate::favoriteAt) shouldBe false
        mangaUpdate.viewerFlags shouldBe 5
        mangaUpdate.notes shouldBe "merged"
        mangaUpdate.chapterFlags shouldBe null
        coVerify(exactly = 1) { setMangaCategories.await(id, emptyList()) }
        coVerify(exactly = 1) { setExcludedScanlators.await(id, emptySet()) }
        val writes = chapterUpdates.single().associateBy { it.id }
        writes.keys shouldBe setOf(11L, 12L, 13L)
        writes.getValue(11).read shouldBe false
        writes.getValue(11).isSet(ChapterUpdate::bookmark) shouldBe false
        writes.getValue(12).bookmark shouldBe false
        writes.getValue(13).read shouldBe true
        writes.getValue(13).isSet(ChapterUpdate::lastPageRead) shouldBe false
        writes.values.none { it.isSet(ChapterUpdate::dateFetch) } shouldBe true
        // The history clear propagates; the row stays.
        coVerify(exactly = 1) { historyRepository.resetHistory(7) }
        historyUpserts.shouldBeEmpty()
        val upserted = track(trackerId = 1, lastChapterRead = 3.0, id = 0).copy(mangaId = id)
        coVerify(exactly = 1) { trackRepository.upsertAll(listOf(upserted)) }
        coVerify(exactly = 1) { trackRepository.delete(id, 2) }
        overrides[id] shouldBe MangaDedupOverride(mergeSplitChapters = true)
        // Sync never touches chapter rows beyond their state, and never deletes or inserts any.
        coVerify(exactly = 0) { chapterRepository.updateFromRemote(any(), any(), any()) }
        restoreCalls.shouldBeEmpty()
    }

    @Test
    fun `history upserts write the difference to the stored duration`() {
        val key = addManga(
            url = "/h",
            favoriteAt = 1,
            chapters = listOf(
                chapter(id = 21, url = "/h/1"),
                chapter(id = 22, url = "/h/2"),
                chapter(id = 23, url = "/h/3"),
            ),
            history = listOf(
                History(id = 1, chapterId = 21, readAt = Instant.fromEpochMilliseconds(1_000), readDuration = 50),
                // Cleared: the stored duration stays in the row and the upsert adds to it.
                History(id = 2, chapterId = 22, readAt = null, readDuration = 40),
            ),
        )
        snapshot()
        val update = EntryUpdate(
            entry = EntryRecord(key, favorite = true, favoriteAtMillis = 1),
            fields = emptySet(),
            chapters = listOf(
                ChapterRecord("/h/1", historyReadAtMillis = 2_000, historyDurationMs = 80),
                ChapterRecord("/h/2", historyReadAtMillis = 3_000, historyDurationMs = 10),
                ChapterRecord("/h/3", historyReadAtMillis = 4_000, historyDurationMs = 5),
            ),
            upsertTracks = emptyList(),
            deleteTrackerIds = emptyList(),
        )

        val result = applyEntries(LocalChangeSet(updates = listOf(update)))

        result.unrealized.shouldBeEmpty()
        historyUpserts shouldContainExactly listOf(
            HistoryUpdate(21, Instant.fromEpochMilliseconds(2_000), 30),
            HistoryUpdate(22, Instant.fromEpochMilliseconds(3_000), -30),
            HistoryUpdate(23, Instant.fromEpochMilliseconds(4_000), 5),
        )
        chapterUpdates.shouldBeEmpty()
    }

    @Test
    fun `unfavorite clears the favorite time and re-favorite never lets fetched chapters into Updates`() {
        val removed = addManga(url = "/r", favoriteAt = 1_000)
        val early = addManga(
            url = "/e",
            favoriteAt = null,
            chapters = listOf(chapter(id = 31, url = "/e/1", dateFetch = 5_000)),
        )
        val late = addManga(
            url = "/l",
            favoriteAt = null,
            chapters = listOf(chapter(id = 41, url = "/l/1", dateFetch = 9_000)),
        )
        snapshot(knownKeys = setOf(early, late))
        fun favorite(key: EntryKey, favorite: Boolean) = EntryUpdate(
            entry = EntryRecord(key, favorite = favorite, favoriteAtMillis = 6_000),
            fields = setOf(EntryField.FAVORITE),
            chapters = emptyList(),
            upsertTracks = emptyList(),
            deleteTrackerIds = emptyList(),
        )
        val before = Clock.System.now()

        val result = applyEntries(
            LocalChangeSet(updates = listOf(favorite(removed, false), favorite(early, true), favorite(late, true))),
        )

        result.unrealized.shouldBeEmpty()
        val byId = mangaUpdates.single().associateBy { update -> mangas.single { it.id == update.id }.url }
        byId.getValue("/r").isSet(MangaUpdate::favoriteAt) shouldBe true
        byId.getValue("/r").favoriteAt shouldBe null
        byId.getValue("/e").favoriteAt shouldBe Instant.fromEpochMilliseconds(6_000)
        // A chapter fetched after the merged time would show in Updates, so the entry is re-added now.
        val now = byId.getValue("/l").favoriteAt!!
        (now >= before.truncatedToMillis() && now <= Clock.System.now()) shouldBe true
    }

    @Test
    fun `chapter states are written in transactions of at most 2000 rows`() {
        val many = (1..4_500).map { chapter(id = it.toLong(), url = "/big/$it") }
        val key = addManga(url = "/big", favoriteAt = 1, chapters = many)
        snapshot()
        val update = EntryUpdate(
            entry = EntryRecord(key, favorite = true, favoriteAtMillis = 1),
            fields = emptySet(),
            chapters = many.map { ChapterRecord(it.url, read = true) },
            upsertTracks = emptyList(),
            deleteTrackerIds = emptyList(),
        )

        applyEntries(LocalChangeSet(updates = listOf(update))).unrealized.shouldBeEmpty()

        chapterUpdates.map { it.size } shouldContainExactly listOf(2_000, 2_000, 500)
    }

    @Test
    fun `an entry changed since the snapshot is skipped without writes`() {
        val key = addManga(url = "/a", favoriteAt = 1, chapters = listOf(chapter(id = 11, url = "/a/1")))
        snapshot()
        // The user reads the chapter between the merge and the apply.
        chapters[mangas.single().id] = listOf(chapter(id = 11, url = "/a/1", read = true))
        val update = EntryUpdate(
            entry = EntryRecord(key, favorite = true, favoriteAtMillis = 1, notes = "remote"),
            fields = setOf(EntryField.NOTES),
            chapters = listOf(ChapterRecord("/a/1", bookmark = true)),
            upsertTracks = emptyList(),
            deleteTrackerIds = emptyList(),
        )

        val result = applyEntries(LocalChangeSet(updates = listOf(update)))

        result.skipped shouldContainExactly setOf(key)
        result.unrealized.shouldBeEmpty()
        mangaUpdates.shouldBeEmpty()
        chapterUpdates.shouldBeEmpty()
    }

    @Test
    fun `a membership dropped by a category delete does not trip the guard`() {
        val key = addManga(url = "/a", favoriteAt = 1, categoryIds = listOf(1, 2))
        snapshot()
        memberships[mangas.single().id] = listOf(2)
        val update = EntryUpdate(
            entry = EntryRecord(key, favorite = true, favoriteAtMillis = 1, categoryUuids = listOf("u-done")),
            fields = setOf(EntryField.CATEGORIES),
            chapters = emptyList(),
            upsertTracks = emptyList(),
            deleteTrackerIds = emptyList(),
        )

        val result = applyEntries(LocalChangeSet(updates = listOf(update)), categoryLocalIds = mapOf("u-done" to 2L))

        result.skipped.shouldBeEmpty()
        coVerify(exactly = 1) { setMangaCategories.await(mangas.single().id, listOf(2L)) }
    }

    @Test
    fun `a write the repository swallowed is reported unrealized with the actual state`() {
        val key = addManga(url = "/a", favoriteAt = 1, chapters = listOf(chapter(id = 11, url = "/a/1")))
        snapshot()
        coEvery { historyRepository.upsertHistory(any()) } returns Unit
        val update = EntryUpdate(
            entry = EntryRecord(key, favorite = true, favoriteAtMillis = 1),
            fields = emptySet(),
            chapters = listOf(ChapterRecord("/a/1", read = true, historyReadAtMillis = 2_000, historyDurationMs = 5)),
            upsertTracks = emptyList(),
            deleteTrackerIds = emptyList(),
        )

        val result = applyEntries(LocalChangeSet(updates = listOf(update)))

        result.skipped.shouldBeEmpty()
        result.unrealized.keys shouldContainExactly setOf(key)
        result.unrealized.getValue(key).chapters shouldContainExactly listOf(ChapterRecord("/a/1", read = true))
    }

    @Test
    fun `categories delete before they create, so a collapse can reuse the deleted name`() {
        categories.clear()
        categories += Category(id = 5, name = "Manga", order = 0, flags = 0)
        categories += Category(id = 6, name = "Old", order = 1, flags = 0)
        val observed = runBlocking { reader.readCategories() }
        observed.size shouldBe 2
        val changes = CategoryChangeSet(
            create = listOf(CategoryRecord("u-a", "Manga", order = 0, flags = 8)),
            rename = listOf(CategoryRecord("u-c", "New", order = 1, flags = 4)),
            updateFlags = listOf(CategoryRecord("u-c", "New", order = 1, flags = 4)),
            order = listOf("u-c", "u-a"),
            delete = listOf("u-b"),
        )

        val result = runBlocking { applier.applyCategories(changes, mapOf("u-b" to 5L, "u-c" to 6L)) }

        result.skipped shouldBe false
        val created = categories.single { it.name == "Manga" }.id
        result.localIds shouldBe mapOf("u-a" to created, "u-c" to 6L)
        coVerifyOrder {
            deleteCategory.await(5)
            categoryRepository.insert(NewCategory("Manga", 8))
            categoryRepository.updateName(6, "New")
            categoryRepository.updateFlags(6, 4)
            categoryRepository.updateAllOrders(listOf(6L, created))
        }
    }

    @Test
    fun `categories changed since the snapshot are skipped`() {
        runBlocking { reader.readCategories() }
        categories[0] = reading.copy(name = "Renamed")
        val changes = CategoryChangeSet(rename = listOf(CategoryRecord("u-reading", "Remote")))

        val result = runBlocking { applier.applyCategories(changes, categoryIds) }

        result shouldBe CategoryApplyResult(categoryIds, skipped = true)
        coVerify(exactly = 0) { categoryRepository.updateName(any(), any()) }
    }

    @Test
    fun `prefs are written with typed setters`() {
        val prefs = stubPrefs()
        runBlocking { reader.readPrefs() }
        val changes = PrefChangeSet(
            set = listOf(
                PrefRecord("haruka_panorama_covers", "true"),
                PrefRecord("haruka_animation_profile", "REDUCED"),
                PrefRecord("haruka_compact_titles", "garbage"),
                PrefRecord("haruka_beta_updates", "true"),
            ),
            remove = listOf("haruka_dedup_rules"),
        )

        val result = runBlocking { applier.applyPrefs(changes) }

        result.skipped shouldBe false
        @Suppress("UNCHECKED_CAST")
        verify(exactly = 1) { (prefs.getValue("haruka_panorama_covers") as Preference<Boolean>).set(true) }
        @Suppress("UNCHECKED_CAST")
        verify(exactly = 1) { (prefs.getValue("haruka_animation_profile") as Preference<String>).set("REDUCED") }
        verify(exactly = 1) { prefs.getValue("haruka_dedup_rules").delete() }
        @Suppress("UNCHECKED_CAST")
        verify(exactly = 0) { (prefs.getValue("haruka_compact_titles") as Preference<Boolean>).set(any()) }
        verify(exactly = 0) { preferenceStore.getBoolean("haruka_beta_updates", any()) }
    }

    @Test
    fun `prefs changed since the snapshot are skipped`() {
        val prefs = stubPrefs()
        runBlocking { reader.readPrefs() }
        val group = prefs.getValue("haruka_group_updates")
        every { group.isSet() } returns true
        every { group.get() } returns true

        val changes = PrefChangeSet(set = listOf(PrefRecord("haruka_panorama_covers", "true")))
        val result = runBlocking { applier.applyPrefs(changes) }

        result.skipped shouldBe true
        @Suppress("UNCHECKED_CAST")
        verify(exactly = 0) { (prefs.getValue("haruka_panorama_covers") as Preference<Boolean>).set(any()) }
    }

    private fun applyEntries(changes: LocalChangeSet, categoryLocalIds: Map<String, Long> = categoryIds) =
        runBlocking { applier.applyEntries(changes, categoryLocalIds) }

    /** The engine's read of this device before the merge. */
    private fun snapshot(knownKeys: Set<EntryKey> = emptySet()) = runBlocking {
        val uuids = categoryIds.entries.associate { (uuid, id) -> id to uuid }
        for (index in 0..<BUCKET_COUNT) {
            reader.readBucket(index, knownKeys.filterTo(HashSet()) { bucketOf(it) == index }, uuids)
        }
    }

    private fun addManga(
        url: String,
        favoriteAt: Long?,
        viewerFlags: Long = 0,
        chapters: List<Chapter> = emptyList(),
        history: List<History> = emptyList(),
        tracks: List<Track> = emptyList(),
        categoryIds: List<Long> = emptyList(),
        excluded: Set<String> = emptySet(),
    ): EntryKey {
        val id = nextId++
        mangas += Manga.create().copy(
            id = id,
            source = 1,
            url = url,
            favoriteAt = favoriteAt?.let(Instant::fromEpochMilliseconds),
            viewerFlags = viewerFlags,
        )
        this.chapters[id] = chapters.map { it.copy(mangaId = id) }
        this.history[id] = history
        this.tracks[id] = tracks.map { it.copy(mangaId = id) }
        memberships[id] = categoryIds
        this.excluded[id] = excluded
        return EntryKey(1, url)
    }

    private fun chapter(
        id: Long,
        url: String,
        read: Boolean = false,
        bookmark: Boolean = false,
        lastPageRead: Int = 0,
        dateFetch: Long = 0,
    ) = Chapter.create().copy(
        id = id,
        url = url,
        read = read,
        bookmark = bookmark,
        lastPageRead = lastPageRead,
        dateFetch = Instant.fromEpochMilliseconds(dateFetch),
        chapterNumber = 0.0,
    )

    private fun track(trackerId: Long, lastChapterRead: Double = 0.0, id: Long = trackerId) = Track(
        id = id,
        mangaId = 0,
        trackerId = trackerId,
        remoteId = 0,
        libraryId = null,
        title = "",
        lastChapterRead = lastChapterRead,
        totalChapters = 0,
        status = 0,
        score = 0.0,
        remoteUrl = "",
        startDate = null,
        finishDate = null,
        private = false,
    )

    private fun Instant.truncatedToMillis() = Instant.fromEpochMilliseconds(toEpochMilliseconds())

    private fun stubPrefs(): Map<String, Preference<*>> {
        val prefs = SYNCED_PREF_STORAGE.mapValues { (key, storage) ->
            when (storage) {
                PrefStorage.BOOLEAN -> mockk<Preference<Boolean>>(relaxed = true).also {
                    every { preferenceStore.getBoolean(key, any()) } returns it
                }
                PrefStorage.STRING -> mockk<Preference<String>>(relaxed = true).also {
                    every { preferenceStore.getString(key, any()) } returns it
                }
            }
        }
        for (pref in prefs.values) every { pref.isSet() } returns false
        return prefs
    }

    private fun <V> Map<Long, V>.slice(ids: List<Long>): Map<Long, V> = filterKeys { it in ids }

    private fun find(id: Long) = mangas.indexOfFirst { it.id == id }

    private fun stubRepositories() {
        coEvery { mangaRepository.getFavorites() } answers { mangas.filter { it.favorite } }
        coEvery { mangaRepository.getMangaByUrlAndSourceId(any(), any()) } answers {
            mangas.firstOrNull { it.url == firstArg<String>() && it.source == secondArg<Long>() }
        }
        coEvery { mangaRepository.getExcludedScanlatorsByMangaIds(any()) } answers {
            excluded.slice(firstArg()).mapValues { it.value.toList() }
        }
        coEvery { mangaRepository.updateAll(any()) } answers {
            val updates = firstArg<List<MangaUpdate>>()
            mangaUpdates += updates
            for (update in updates) {
                val i = find(update.id)
                var manga = mangas[i]
                if (update.isSet(MangaUpdate::favoriteAt)) manga = manga.copy(favoriteAt = update.favoriteAt)
                update.viewerFlags?.let { manga = manga.copy(viewerFlags = it) }
                update.chapterFlags?.let { manga = manga.copy(chapterFlags = it) }
                update.notes?.let { manga = manga.copy(notes = it) }
                mangas[i] = manga
            }
            true
        }
        coEvery { setMangaCategories.await(any(), any()) } answers { memberships[firstArg()] = secondArg() }
        coEvery { setExcludedScanlators.await(any(), any()) } answers { excluded[firstArg()] = secondArg() }

        coEvery { chapterRepository.getChaptersByMangaIds(any()) } answers { chapters.slice(firstArg()) }
        coEvery { chapterRepository.updateAll(any()) } answers {
            val updates = firstArg<List<ChapterUpdate>>()
            chapterUpdates += updates
            val byId = updates.associateBy { it.id }
            for ((mangaId, rows) in chapters.entries.toList()) {
                chapters[mangaId] = rows.map { row ->
                    val update = byId[row.id] ?: return@map row
                    row.copy(
                        read = update.read ?: row.read,
                        bookmark = update.bookmark ?: row.bookmark,
                        lastPageRead = update.lastPageRead ?: row.lastPageRead,
                    )
                }
            }
        }

        coEvery { historyRepository.getHistoryByMangaIds(any()) } answers { history.slice(firstArg()) }
        coEvery { historyRepository.upsertHistory(any()) } answers {
            val update = firstArg<HistoryUpdate>()
            historyUpserts += update
            val mangaId = chapters.entries.first { (_, rows) -> rows.any { it.id == update.chapterId } }.key
            val rows = history[mangaId].orEmpty()
            val existing = rows.firstOrNull { it.chapterId == update.chapterId }
            history[mangaId] = if (existing == null) {
                rows + History(nextId++, update.chapterId, update.readAt, update.sessionReadDuration)
            } else {
                rows.map {
                    if (it !== existing) {
                        it
                    } else {
                        it.copy(readAt = update.readAt, readDuration = it.readDuration + update.sessionReadDuration)
                    }
                }
            }
        }
        coEvery { historyRepository.resetHistory(any()) } answers {
            val historyId = firstArg<Long>()
            for ((mangaId, rows) in history.entries.toList()) {
                history[mangaId] = rows.map { if (it.id == historyId) it.copy(readAt = null) else it }
            }
        }

        coEvery { trackRepository.getTracksByMangaIds(any()) } answers { tracks.slice(firstArg()) }
        coEvery { trackRepository.upsertAll(any()) } answers {
            for (track in firstArg<List<Track>>()) {
                val others = tracks[track.mangaId].orEmpty().filter { it.trackerId != track.trackerId }
                tracks[track.mangaId] = others + track
            }
        }
        coEvery { trackRepository.delete(any(), any()) } answers {
            tracks[firstArg()] = tracks[firstArg()].orEmpty().filter { it.trackerId != secondArg<Long>() }
        }

        coEvery { categoryRepository.getAll() } answers { categories.toList() }
        coEvery { categoryRepository.getCategoriesByMangaIds(any()) } answers {
            memberships.slice(firstArg()).mapValues { (_, ids) -> categories.filter { it.id in ids } }
        }
        coEvery { categoryRepository.insert(any()) } answers {
            val new = firstArg<NewCategory>()
            categories += Category(nextId++, new.name, (categories.maxOfOrNull { it.order } ?: -1) + 1, new.flags)
        }
        coEvery { categoryRepository.updateName(any(), any()) } answers {
            val i = categories.indexOfFirst { it.id == firstArg<Long>() }
            categories[i] = categories[i].copy(name = secondArg())
        }
        coEvery { categoryRepository.updateFlags(any(), any()) } answers {
            val i = categories.indexOfFirst { it.id == firstArg<Long>() }
            categories[i] = categories[i].copy(flags = secondArg())
        }
        coEvery { categoryRepository.updateAllOrders(any()) } returns Unit
        coEvery { deleteCategory.await(any()) } answers {
            val id = firstArg<Long>()
            categories.removeAll { it.id == id }
            for ((mangaId, ids) in memberships.entries.toList()) memberships[mangaId] = ids - id
            DeleteCategory.Result.Success
        }

        every { dedupSettings.allOverrides() } answers { overrides }
        every { dedupSettings.setOverride(any(), any()) } answers {
            val override = secondArg<MangaDedupOverride?>()
            val id = firstArg<Long>()
            overrides = if (override == null) overrides - id else overrides + (id to override)
        }

        every { fetchInterval.getWindow(any(), any()) } returns Instant.DISTANT_PAST..Instant.DISTANT_FUTURE
        coEvery { fetchInterval.withFetchInterval(any(), any(), any(), any(), any()) } answers {
            MangaUpdate(firstArg<Manga>().id) {}
        }
        coEvery { restoreRepository.restoreManga(any(), any()) } coAnswers {
            val entries = firstArg<List<RestoredManga>>()
            val update = secondArg<suspend (Manga) -> MangaUpdate>()
            restoreCalls += entries
            for (entry in entries) {
                val id = nextId++
                val manga = entry.manga.copy(id = id)
                mangas += manga
                chapters[id] = entry.chapters.map { it.copy(id = nextId++, mangaId = id) }
                history[id] = entry.history.map { restored ->
                    val chapterId = chapters.getValue(id).first { it.url == restored.chapterUrl }.id
                    History(nextId++, chapterId, restored.readAt, restored.readDuration)
                }
                tracks[id] = entry.tracks.map { it.copy(mangaId = id) }
                memberships[id] = entry.categoryIds
                excluded[id] = entry.excludedScanlators.toSet()
                update(manga)
            }
        }
    }
}
