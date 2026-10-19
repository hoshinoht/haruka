package haruka.sync.local

import dev.zacsweers.metro.Inject
import haruka.chapter.dedup.DedupSettings
import haruka.chapter.dedup.MangaDedupOverride
import haruka.sync.codec.SyncCodec
import haruka.sync.engine.LocalCategoryRow
import haruka.sync.engine.LocalSource
import haruka.sync.merge.SYNCED_PREF_KEYS
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
import haruka.sync.model.canonical
import kotlinx.serialization.json.JsonObject
import logcat.LogPriority
import logcat.logcat
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.history.model.History
import tachiyomi.domain.history.repository.HistoryRepository
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.track.model.Track
import tachiyomi.domain.track.repository.TrackRepository
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * Reads the local side of a sync (plan section "Sync scope") through the upstream repositories: the library's
 * entries with their chapter rows, history, tracks, categories, excluded scanlators and dedup overrides, the user
 * categories and the whitelisted fork preferences. Read-only; no schema of its own.
 *
 * Chapter presence: every record lists exactly the chapter rows that exist locally, with their observed state. The
 * merge derives UNKNOWN (base-known row missing) and NEW-LOCAL (row the base saw as absent) from that and the base's
 * `localPresent` flags, so nothing here consults the base beyond which entries to read.
 *
 * It also remembers what one sync observed (categories, prefs and a digest per entry), which [LocalLibraryApplier]
 * compares with the rows it is about to write. One instance therefore serves one sync at a time, as both the
 * engine's [LocalSource] and the applier's snapshot; a new pass starts at bucket 0.
 */
@Inject
class LocalSnapshotReader(
    private val mangaRepository: MangaRepository,
    private val chapterRepository: ChapterRepository,
    private val historyRepository: HistoryRepository,
    private val trackRepository: TrackRepository,
    private val categoryRepository: CategoryRepository,
    private val dedupSettings: DedupSettings,
    private val preferenceStore: PreferenceStore,
) : LocalSource {

    /** Favorites by bucket, loaded when a pass starts. */
    private var favorites: Array<ArrayList<Manga>>? = null
    private var favoriteKeys: Set<EntryKey> = emptySet()
    private var overrides: Map<Long, MangaDedupOverride> = emptyMap()

    /** Favorite records of buckets read ahead with an earlier bucket's batch. */
    private val readAhead = HashMap<Int, List<EntryRecord>>()

    private val observedEntries = HashMap<EntryKey, ObservedEntry>()
    internal var observedCategories: List<LocalCategoryRow>? = null
        private set
    internal var observedPrefs: PrefsPayload? = null
        private set

    // Collector time is excluded, so the logged figure is the snapshot's own cost.
    private var elapsed = Duration.ZERO
    private var entryCount = 0
    private var chapterCount = 0

    /** User categories in display order; the system "Default" category never syncs. */
    override suspend fun readCategories(): List<LocalCategoryRow> = currentCategories().also { observedCategories = it }

    /** The whitelisted fork preferences that are set on this device, in their stored string form. */
    override suspend fun readPrefs(): PrefsPayload = currentPrefs().also { observedPrefs = it }

    /**
     * Rows are fetched for whole buckets at up to [BATCH_SIZE] favorites per batch (a single larger bucket is one
     * batch); the records of the following buckets in a batch are held until they are asked for, so only one
     * batch's chapters are in memory at once. Non-favorite [knownKeys] (recent removals, tombstones; usually a
     * handful) are looked up one by one.
     *
     * @param categoryUuids local category id to uuid for every user category (`CategoryMerge.assignUuids`).
     */
    override suspend fun readBucket(
        index: Int,
        knownKeys: Set<EntryKey>,
        categoryUuids: Map<Long, String>,
    ): List<EntryRecord> {
        val mark = TimeSource.Monotonic.markNow()
        if (index == 0 || favorites == null) startPass()
        val uuidOf = categoryUuids::requireUuid

        val records = ArrayList(readAhead.remove(index) ?: readFavorites(index, uuidOf))
        val others = knownKeys.filter { it !in favoriteKeys }.mapNotNull { find(it) }
        if (others.isNotEmpty()) readRows(others, uuidOf, overrides).mapTo(records) { it.record }
        records.sortBy { it.key }
        for (record in records) {
            observedEntries[record.key] = record.observe()
            chapterCount += record.chapters.size
        }
        entryCount += records.size
        elapsed += mark.elapsedNow()

        if (index == BUCKET_COUNT - 1) {
            logcat(LogPriority.INFO, SYNC_LOG_TAG) {
                "timing phase=snapshot ms=${elapsed.inWholeMilliseconds} entries=$entryCount chapters=$chapterCount"
            }
        }
        return records
    }

    private suspend fun startPass() {
        val byBucket = Array(BUCKET_COUNT) { ArrayList<Manga>() }
        val keys = HashSet<EntryKey>()
        for (manga in mangaRepository.getFavorites()) {
            val key = manga.key()
            if (keys.add(key)) byBucket[bucketOf(key)].add(manga)
        }
        favorites = byBucket
        favoriteKeys = keys
        overrides = dedupSettings.allOverrides()
        readAhead.clear()
        observedEntries.clear()
        elapsed = Duration.ZERO
        entryCount = 0
        chapterCount = 0
    }

    /** Reads the favorites of [index] and of the following buckets that fit in its batch. */
    private suspend fun readFavorites(index: Int, uuidOf: (Long) -> String): List<EntryRecord> {
        val buckets = favorites!!
        var end = index + 1
        var size = buckets[index].size
        while (end < BUCKET_COUNT && end !in readAhead && size + buckets[end].size <= BATCH_SIZE) {
            size += buckets[end++].size
        }
        val batch = (index..<end).flatMap { buckets[it] }
        if (batch.isEmpty()) return emptyList()
        val records = readRows(batch, uuidOf, overrides).groupBy({ bucketOf(it.record.key) }, { it.record })
        for (later in index + 1..<end) readAhead[later] = records[later].orEmpty()
        return records[index].orEmpty()
    }

    /** User categories as the database holds them now. */
    internal suspend fun currentCategories(): List<LocalCategoryRow> = categoryRepository.getAll()
        .filterNot { it.isSystemCategory }
        .map { LocalCategoryRow(it.id, it.name, it.order, it.flags) }

    internal fun currentPrefs(): PrefsPayload = PrefsPayload(
        SYNCED_PREF_KEYS.sorted().mapNotNull { key ->
            val preference = preferenceStore.syncedPreference(key)
            if (preference.isSet()) PrefRecord(key, preference.get().toString()) else null
        },
    )

    /** What [readBucket] last returned for [key] in this pass, or null if it did not read the entry. */
    internal fun observed(key: EntryKey): ObservedEntry? = observedEntries[key]

    /**
     * The rows of [keys] that exist now, read fresh without touching the pass's snapshot. Memberships in a category
     * [categoryUuids] does not map (created since) read as `UNMAPPED_CATEGORY_PREFIX + id`, which matches no uuid.
     */
    internal suspend fun readCurrent(
        keys: Collection<EntryKey>,
        categoryUuids: Map<Long, String>,
    ): Map<EntryKey, LocalRow> {
        val mangas = keys.mapNotNull { find(it) }
        if (mangas.isEmpty()) return emptyMap()
        val uuidOf = { id: Long -> categoryUuids[id] ?: "$UNMAPPED_CATEGORY_PREFIX$id" }
        return readRows(mangas, uuidOf, dedupSettings.allOverrides()).associateBy { it.record.key }
    }

    private suspend fun find(key: EntryKey): Manga? = mangaRepository.getMangaByUrlAndSourceId(key.url, key.sourceId)

    private suspend fun readRows(
        mangas: List<Manga>,
        uuidOf: (Long) -> String,
        overrides: Map<Long, MangaDedupOverride>,
    ): List<LocalRow> {
        val ids = mangas.map { it.id }
        val chapters = chapterRepository.getChaptersByMangaIds(ids)
        val history = historyRepository.getHistoryByMangaIds(ids)
        val tracks = trackRepository.getTracksByMangaIds(ids)
        val categories = categoryRepository.getCategoriesByMangaIds(ids)
        val excluded = mangaRepository.getExcludedScanlatorsByMangaIds(ids)
        return mangas.map { manga ->
            val mangaChapters = chapters[manga.id].orEmpty()
            val historyByChapter = history[manga.id].orEmpty().associateBy { it.chapterId }
            val record = manga.toEntryRecord(
                key = manga.key(),
                categoryUuids = categories[manga.id].orEmpty().mapNotNull { category ->
                    // Membership in "Default" is the absence of any other; it never appears as a row anyway.
                    if (category.isSystemCategory) null else uuidOf(category.id)
                },
                excludedScanlators = excluded[manga.id].orEmpty(),
                dedupOverride = overrides[manga.id]?.toRecord(),
                chapters = mangaChapters.map { it.toChapterRecord(historyByChapter[it.id]) },
                tracks = tracks[manga.id].orEmpty().map { it.toTrackRecord() },
            )
            LocalRow(manga, mangaChapters, historyByChapter, record)
        }
    }

    private companion object {
        const val BATCH_SIZE = 100
    }
}

internal const val SYNC_LOG_TAG = "HarukaSync"

/** Prefix of the placeholder uuid [LocalSnapshotReader.readCurrent] gives a category without a sync uuid. */
internal const val UNMAPPED_CATEGORY_PREFIX = "unmapped:"

/** A library row as read now: the domain objects the applier writes against, and the record they project to. */
internal class LocalRow(
    val manga: Manga,
    val chapters: List<Chapter>,
    /** By chapter id. */
    val history: Map<Long, History>,
    val record: EntryRecord,
)

/**
 * An entry as the snapshot read it: a digest of its synced state without category memberships, plus the
 * memberships, which the category apply may legitimately change (a deleted category drops its members).
 */
internal class ObservedEntry(private val digest: String, private val categoryUuids: List<String>) {

    /**
     * True when [current] still holds the observed state. [liveUuids] are the categories that exist after the
     * category apply; memberships in the others are gone with them.
     */
    fun matches(current: EntryRecord, liveUuids: Set<String>): Boolean =
        current.categoryUuids == categoryUuids.filter { it in liveUuids } && current.digestWithoutCategories() == digest
}

private fun EntryRecord.observe() = ObservedEntry(digestWithoutCategories(), categoryUuids)

private fun EntryRecord.digestWithoutCategories(): String = SyncCodec.entryDigest(copy(categoryUuids = emptyList()))

private fun Manga.key() = EntryKey(source, url)

// A membership dropped for want of a uuid would read as a removal, so an unmapped category is a caller bug.
private fun Map<Long, String>.requireUuid(categoryId: Long): String =
    checkNotNull(this[categoryId]) { "Category $categoryId has no sync uuid; assign uuids before reading" }

private fun Manga.toEntryRecord(
    key: EntryKey,
    categoryUuids: List<String>,
    excludedScanlators: List<String>,
    dedupOverride: DedupOverrideRecord?,
    chapters: List<ChapterRecord>,
    tracks: List<TrackRecord>,
) = EntryRecord(
    key = key,
    favorite = favorite,
    favoriteAtMillis = favoriteAt?.takeUnless { it == Manga.UNKNOWN_FAVORITE_AT }?.toEpochMilliseconds(),
    title = title,
    author = author,
    artist = artist,
    description = description,
    genre = genre.orEmpty(),
    status = status,
    thumbnailUrl = thumbnailUrl,
    initialized = initialized,
    updateStrategy = updateStrategy.ordinal,
    memoJson = memo.toJsonText(),
    viewerFlags = viewerFlags,
    chapterFlags = chapterFlags,
    notes = notes,
    categoryUuids = categoryUuids,
    excludedScanlators = excludedScanlators,
    dedupOverride = dedupOverride,
    chapters = chapters,
    tracks = tracks,
).canonical()

private fun Chapter.toChapterRecord(history: History?): ChapterRecord {
    // A duration without a read time can't be applied, so it is 0 in the snapshot, the merge and the digest.
    val readHistory = history?.takeIf { it.readAt != null }
    return ChapterRecord(
        url = url,
        name = name,
        chapterNumber = chapterNumber,
        scanlator = scanlator,
        sourceOrder = sourceOrder,
        dateUploadMillis = dateUpload?.toEpochMilliseconds(),
        memoJson = memo.toJsonText(),
        read = read,
        bookmark = bookmark,
        lastPageRead = lastPageRead.toLong(),
        historyReadAtMillis = readHistory?.readAt?.toEpochMilliseconds(),
        historyDurationMs = readHistory?.readDuration ?: 0,
    )
}

private fun Track.toTrackRecord() = TrackRecord(
    trackerId = trackerId,
    remoteId = remoteId,
    libraryId = libraryId,
    title = title,
    lastChapterRead = lastChapterRead,
    totalChapters = totalChapters,
    status = status,
    score = score,
    remoteUrl = remoteUrl,
    startDateMillis = startDate?.toEpochMilliseconds(),
    finishDateMillis = finishDate?.toEpochMilliseconds(),
    private = private,
)

private fun MangaDedupOverride.toRecord() = DedupOverrideRecord(
    groupChapters = groupChapters,
    mergeSplitChapters = mergeSplitChapters,
    rankedScanlators = rankedScanlators?.let(::ScanlatorRanking),
)

private fun JsonObject.toJsonText(): String = if (isEmpty()) "" else toString()
