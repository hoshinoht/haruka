package haruka.sync.local

import dev.zacsweers.metro.Inject
import haruka.chapter.dedup.DedupSettings
import haruka.chapter.dedup.MangaDedupOverride
import haruka.sync.merge.LocalCategory
import haruka.sync.merge.SYNCED_PREF_KEYS
import haruka.sync.model.BUCKET_COUNT
import haruka.sync.model.CategoryRecord
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonObject
import logcat.LogPriority
import logcat.logcat
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
import kotlin.time.Duration
import kotlin.time.TimeSource

/** One bucket of the local projection: this device's rows whose [bucketOf] is [index], canonical and sorted by key. */
data class LocalBucket(val index: Int, val entries: List<EntryRecord>)

/**
 * Reads the local side of a sync (plan section "Sync scope") through the upstream repositories: the library's
 * entries with their chapter rows, history, tracks, categories, excluded scanlators and dedup overrides, the user
 * categories and the whitelisted fork preferences. Read-only; no schema of its own.
 *
 * Chapter presence: every record lists exactly the chapter rows that exist locally, with their observed state. The
 * merge derives UNKNOWN (base-known row missing) and NEW-LOCAL (row the base saw as absent) from that and the base's
 * `localPresent` flags, so nothing here consults the base beyond which entries to read.
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
) {

    /** User categories in display order; the system "Default" category never syncs. */
    suspend fun readCategories(): List<Category> = categoryRepository.getAll().filterNot { it.isSystemCategory }

    /** The whitelisted fork preferences that are set on this device, in their stored string form. */
    fun readPrefs(): PrefsPayload = PrefsPayload(
        SYNCED_PREF_KEYS.sorted().mapNotNull { key ->
            val preference = when (PREF_STORAGE[key] ?: error("No storage type known for synced pref $key")) {
                PrefStorage.BOOLEAN -> preferenceStore.getBoolean(key)
                PrefStorage.STRING -> preferenceStore.getString(key)
            }
            if (preference.isSet()) PrefRecord(key, preference.get().toString()) else null
        },
    )

    /**
     * Streams the local entries one bucket at a time: every index in `0 until BUCKET_COUNT`, ascending, empty
     * buckets included, since each still has to be merged against the base and the remote.
     *
     * The entries are the favorites plus every entry of [baseKeys] that still has a row, in the library or not (an
     * unfavorited entry must read as favorite = false, a deleted row as absent). Rows are fetched for whole buckets
     * at up to [BATCH_SIZE] entries per batch (a single larger bucket is one batch), so only one batch's chapters
     * are held at once as long as the collector does not keep the buckets it receives.
     *
     * @param categoryUuids local category id to uuid for every user category (`CategoryMerge.assignUuids`).
     */
    fun readEntries(baseKeys: Set<EntryKey>, categoryUuids: Map<Long, String>): Flow<LocalBucket> = flow {
        // Collector time is excluded, so the logged figure is the snapshot's own cost.
        var elapsed = Duration.ZERO
        var entryCount = 0
        var chapterCount = 0
        var mark = TimeSource.Monotonic.markNow()
        val buckets = candidatesByBucket(baseKeys)
        val overrides = dedupSettings.allOverrides()
        elapsed += mark.elapsedNow()

        var next = 0
        while (next < BUCKET_COUNT) {
            mark = TimeSource.Monotonic.markNow()
            val first = next
            var size = buckets[next++].size
            while (next < BUCKET_COUNT && size + buckets[next].size <= BATCH_SIZE) size += buckets[next++].size
            val batch = (first..<next).flatMap { buckets[it] }
            val records = if (batch.isEmpty()) emptyMap() else readBatch(batch, categoryUuids, overrides)
            entryCount += records.size
            for (record in records.values) chapterCount += record.chapters.size
            elapsed += mark.elapsedNow()

            for (index in first..<next) {
                emit(LocalBucket(index, buckets[index].map { records.getValue(it.manga.id) }))
            }
        }
        logcat(LogPriority.INFO, SYNC_LOG_TAG) {
            "timing phase=snapshot ms=${elapsed.inWholeMilliseconds} entries=$entryCount chapters=$chapterCount"
        }
    }

    private suspend fun candidatesByBucket(baseKeys: Set<EntryKey>): Array<ArrayList<Candidate>> {
        val buckets = Array(BUCKET_COUNT) { ArrayList<Candidate>() }
        val seen = HashSet<EntryKey>()
        fun add(manga: Manga) {
            val key = EntryKey(manga.source, manga.url)
            if (seen.add(key)) buckets[bucketOf(key)].add(Candidate(key, manga))
        }
        mangaRepository.getFavorites().forEach(::add)
        // Usually a handful (recent removals and tombstones), so one lookup each beats loading every row.
        for (key in baseKeys) {
            if (key in seen) continue
            mangaRepository.getMangaByUrlAndSourceId(key.url, key.sourceId)?.let(::add)
        }
        for (bucket in buckets) bucket.sortBy { it.key }
        return buckets
    }

    /** Entry records of [batch], keyed by local manga id. */
    private suspend fun readBatch(
        batch: List<Candidate>,
        categoryUuids: Map<Long, String>,
        overrides: Map<Long, MangaDedupOverride>,
    ): Map<Long, EntryRecord> {
        val ids = batch.map { it.manga.id }
        val chapters = chapterRepository.getChaptersByMangaIds(ids)
        val history = historyRepository.getHistoryByMangaIds(ids)
        val tracks = trackRepository.getTracksByMangaIds(ids)
        val categories = categoryRepository.getCategoriesByMangaIds(ids)
        val excluded = mangaRepository.getExcludedScanlatorsByMangaIds(ids)
        val records = HashMap<Long, EntryRecord>(batch.size * 2)
        for ((key, manga) in batch) {
            val historyByChapter = history[manga.id].orEmpty().associateBy { it.chapterId }
            records[manga.id] = manga.toEntryRecord(
                key = key,
                categoryUuids = categories[manga.id].orEmpty().mapNotNull { category ->
                    // Membership in "Default" is the absence of any other; it never appears as a row anyway.
                    if (category.isSystemCategory) null else categoryUuids.uuidOf(category.id)
                },
                excludedScanlators = excluded[manga.id].orEmpty(),
                dedupOverride = overrides[manga.id]?.toRecord(),
                chapters = chapters[manga.id].orEmpty().map { it.toChapterRecord(historyByChapter[it.id]) },
                tracks = tracks[manga.id].orEmpty().map { it.toTrackRecord() },
            )
        }
        return records
    }

    private data class Candidate(val key: EntryKey, val manga: Manga)

    private enum class PrefStorage { BOOLEAN, STRING }

    private companion object {
        const val BATCH_SIZE = 100
        const val SYNC_LOG_TAG = "HarukaSync"

        /**
         * How each synced key is stored. Enum and object preferences are stored as strings, so their stored form is
         * the string itself; booleans must be read as booleans (SharedPreferences rejects a typed mismatch).
         */
        val PREF_STORAGE = mapOf(
            "haruka_panorama_covers" to PrefStorage.BOOLEAN,
            "haruka_group_updates" to PrefStorage.BOOLEAN,
            "haruka_related_manga" to PrefStorage.BOOLEAN,
            "haruka_compact_titles" to PrefStorage.BOOLEAN,
            "haruka_updates_momotalk" to PrefStorage.BOOLEAN,
            "haruka_animation_profile" to PrefStorage.STRING,
            "haruka_dedup_group_chapters" to PrefStorage.BOOLEAN,
            "haruka_dedup_merge_split" to PrefStorage.BOOLEAN,
            "haruka_dedup_mark_duplicates_read" to PrefStorage.BOOLEAN,
            "haruka_dedup_skip_downloaded_duplicates" to PrefStorage.BOOLEAN,
            "haruka_dedup_rules" to PrefStorage.STRING,
            "haruka_dedup_ranking" to PrefStorage.STRING,
        )
    }
}

/**
 * [CategoryMerge.assignUuids][haruka.sync.merge.CategoryMerge.assignUuids] input for these categories;
 * [categoryLocalIds] is the stored uuid to local id map (`SyncState.categoryLocalIds`).
 */
fun List<Category>.toLocalCategories(categoryLocalIds: Map<String, Long>): List<LocalCategory> {
    val uuidById = HashMap<Long, String>(categoryLocalIds.size * 2)
    for ((uuid, id) in categoryLocalIds) uuidById[id] = uuid
    return map { LocalCategory(it.id, uuidById[it.id], it.name) }
}

/** These categories as the merge's local side; [categoryUuids] must map every one of them. */
fun List<Category>.toCategoryRecords(categoryUuids: Map<Long, String>): List<CategoryRecord> = map {
    CategoryRecord(uuid = categoryUuids.uuidOf(it.id), name = it.name, order = it.order, flags = it.flags)
}

// A membership dropped for want of a uuid would read as a removal, so an unmapped category is a caller bug.
private fun Map<Long, String>.uuidOf(categoryId: Long): String =
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
