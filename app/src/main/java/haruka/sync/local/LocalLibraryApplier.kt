package haruka.sync.local

import dev.zacsweers.metro.Inject
import eu.kanade.domain.manga.interactor.SetExcludedScanlators
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import haruka.chapter.dedup.DedupSettings
import haruka.chapter.dedup.MangaDedupOverride
import haruka.sync.codec.SyncCodec
import haruka.sync.engine.CategoryApplyResult
import haruka.sync.engine.EntryApplyResult
import haruka.sync.engine.LocalApplier
import haruka.sync.engine.PrefApplyResult
import haruka.sync.merge.CategoryChangeSet
import haruka.sync.merge.EntryField
import haruka.sync.merge.EntryUpdate
import haruka.sync.merge.LocalChangeSet
import haruka.sync.merge.PrefChangeSet
import haruka.sync.model.ChapterRecord
import haruka.sync.model.DedupOverrideRecord
import haruka.sync.model.EntryKey
import haruka.sync.model.EntryRecord
import haruka.sync.model.TrackRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.yield
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import logcat.LogPriority
import logcat.logcat
import mihon.core.common.extensions.EMPTY
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.backup.model.RestoredHistory
import tachiyomi.domain.backup.model.RestoredManga
import tachiyomi.domain.backup.repository.RestoreRepository
import tachiyomi.domain.category.interactor.DeleteCategory
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.category.model.NewCategory
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
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

/**
 * Writes a merge into Mihon's database through its repositories and interactors (plan section "Apply path"); no
 * schema of its own and no upstream hook.
 *
 * - Entries missing locally go in through Mihon's restore path, at most [RESTORE_BATCH] per transaction, with
 *   their full chapter index fetched at their favorite time: `updatesView` lists only chapters fetched strictly
 *   after it, so they never show in Updates, and the next library update finds nothing new to auto-download.
 * - Existing rows get exact state (unfavorite, unread, unbookmark, uncategorize and history clears included) in
 *   batches of at most [ROW_BATCH] rows. Sync never inserts chapter rows into an existing entry and never deletes
 *   chapter rows; a removal only unfavorites.
 * - Optimistic guard: right before writing, the rows are read again and compared with what [source] observed for
 *   the merge; an entry (or the categories, or the prefs) changed since is skipped, so the base keeps its pre-merge
 *   value and the next sync merges the user's newer edit.
 * - Afterwards the written entries are read again; one that doesn't hold the merged value (a write the repository
 *   swallowed) is reported unrealized with its actual state.
 *
 * [source] must be the engine's [haruka.sync.engine.LocalSource] for the same sync.
 */
@Inject
class LocalLibraryApplier(
    val source: LocalSnapshotReader,
    private val mangaRepository: MangaRepository,
    private val chapterRepository: ChapterRepository,
    private val historyRepository: HistoryRepository,
    private val trackRepository: TrackRepository,
    private val categoryRepository: CategoryRepository,
    private val restoreRepository: RestoreRepository,
    private val fetchInterval: FetchInterval,
    private val setMangaCategories: SetMangaCategories,
    private val setExcludedScanlators: SetExcludedScanlators,
    private val deleteCategory: DeleteCategory,
    private val dedupSettings: DedupSettings,
    private val preferenceStore: PreferenceStore,
) : LocalApplier {

    override suspend fun applyCategories(changes: CategoryChangeSet, localIds: Map<String, Long>): CategoryApplyResult {
        val observed = checkNotNull(source.observedCategories) { "Categories applied before they were read" }
        if (source.currentCategories() != observed) {
            logcat(LogPriority.INFO, SYNC_LOG_TAG) { "apply guard skipped=categories" }
            return CategoryApplyResult(localIds, skipped = true)
        }

        val ids = HashMap(localIds)
        // Deletes first: a collapse of equal names creates the surviving uuid under the name the deleted one holds.
        for (uuid in changes.delete) {
            val id = ids.remove(uuid) ?: continue
            val result = deleteCategory.await(id)
            if (result is DeleteCategory.Result.InternalError) throw result.error
        }
        if (changes.create.isNotEmpty()) {
            val existing = categoryRepository.getAll().mapTo(HashSet()) { it.id }
            for (category in changes.create) categoryRepository.insert(NewCategory(category.name, category.flags))
            val created = categoryRepository.getAll().filterNot { it.id in existing }
            for (category in changes.create) {
                val id = created.firstOrNull { it.name == category.name && it.id !in ids.values }?.id
                    ?: error("Created category ${category.uuid} is missing")
                ids[category.uuid] = id
            }
        }
        for (category in changes.rename) categoryRepository.updateName(ids.getValue(category.uuid), category.name)
        for (category in changes.updateFlags) {
            categoryRepository.updateFlags(ids.getValue(category.uuid), category.flags)
        }
        if (changes.order.isNotEmpty()) categoryRepository.updateAllOrders(changes.order.map(ids::getValue))
        return CategoryApplyResult(ids)
    }

    override suspend fun applyEntries(changes: LocalChangeSet, categoryLocalIds: Map<String, Long>): EntryApplyResult {
        val categoryUuids = categoryLocalIds.entries.associate { (uuid, id) -> id to uuid }
        val skipped = HashSet<EntryKey>()
        val favoriteAt = insert(changes.inserts, categoryLocalIds, skipped)
        val updated = update(changes.updates, categoryLocalIds, categoryUuids, skipped)

        val inserted = changes.inserts.filter { it.key in favoriteAt }
        val written = inserted.map { it.key } + updated.map { it.entry.key }
        val after = if (written.isEmpty()) emptyMap() else source.readCurrent(written, categoryUuids)
        val unrealized = HashMap<EntryKey, EntryRecord>()
        for (record in inserted) {
            val actual = after[record.key]?.record
            if (actual == null) {
                skipped += record.key
                continue
            }
            val expected = record.copy(favoriteAtMillis = favoriteAt.getValue(record.key))
            if (SyncCodec.entryDigest(actual) != SyncCodec.entryDigest(expected)) {
                unrealized[record.key] = actual.withoutUnmapped()
            }
        }
        for (update in updated) {
            val key = update.entry.key
            val actual = after[key]?.record
            if (actual == null) {
                // Deleted since the guard read it: nothing to report as held, so the base keeps its pre-merge entry.
                skipped += key
                continue
            }
            if (!update.isRealizedBy(actual)) unrealized[key] = actual.withoutUnmapped()
        }
        if (skipped.isNotEmpty() || unrealized.isNotEmpty()) {
            logcat(LogPriority.INFO, SYNC_LOG_TAG) { "apply skipped=${skipped.size} unrealized=${unrealized.size}" }
        }
        return EntryApplyResult(skipped, unrealized)
    }

    override suspend fun applyPrefs(changes: PrefChangeSet): PrefApplyResult {
        val observed = checkNotNull(source.observedPrefs) { "Prefs applied before they were read" }
        if (source.currentPrefs() != observed) {
            logcat(LogPriority.INFO, SYNC_LOG_TAG) { "apply guard skipped=prefs" }
            return PrefApplyResult(skipped = true)
        }
        for ((key, value) in changes.set) {
            when (SYNCED_PREF_STORAGE[key] ?: continue) {
                PrefStorage.BOOLEAN -> {
                    // A value this build can't store stays unwritten; the next sync pushes the local value instead.
                    val flag = value.toBooleanStrictOrNull()
                    if (flag == null) {
                        logcat(LogPriority.WARN, SYNC_LOG_TAG) { "apply pref=$key unreadable" }
                    } else {
                        preferenceStore.getBoolean(key).set(flag)
                    }
                }
                PrefStorage.STRING -> preferenceStore.getString(key).set(value)
            }
        }
        for (key in changes.remove) {
            if (key in SYNCED_PREF_STORAGE) preferenceStore.syncedPreference(key).delete()
        }
        return PrefApplyResult()
    }

    /**
     * Inserts the entries that still have no row through the restore path; a row that appeared since the snapshot
     * is skipped, as is a batch that fails. Returns the favorite time of each inserted entry.
     */
    private suspend fun insert(
        records: List<EntryRecord>,
        categoryLocalIds: Map<String, Long>,
        skipped: MutableSet<EntryKey>,
    ): Map<EntryKey, Long> {
        if (records.isEmpty()) return emptyMap()
        val fresh = records.filter { record ->
            val exists = mangaRepository.getMangaByUrlAndSourceId(record.key.url, record.key.sourceId) != null
            if (exists) skipped += record.key
            !exists
        }
        val now = Clock.System.now()
        val timeZone = TimeZone.currentSystemDefault()
        val today = now.toLocalDateTime(timeZone)
        val window = fetchInterval.getWindow(today.date, timeZone)
        val favoriteAt = HashMap<EntryKey, Long>()
        for (chunk in fresh.chunked(RESTORE_BATCH)) {
            val times = chunk.associate { it.key to (it.favoriteAtMillis ?: now.toEpochMilliseconds()) }
            val entries = chunk.map {
                it.toRestoredManga(Instant.fromEpochMilliseconds(times.getValue(it.key)), categoryLocalIds)
            }
            try {
                restoreRepository.restoreManga(entries) { manga ->
                    fetchInterval.withFetchInterval(manga, today, timeZone, window)
                }
                favoriteAt += times
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The batch rolled back as a whole, so its entries are cleanly absent and insert again next time.
                logcat(LogPriority.ERROR, SYNC_LOG_TAG) { "apply insert failed entries=${chunk.size} ${e.message}" }
                chunk.mapTo(skipped) { it.key }
            }
            yield()
        }
        for (record in fresh) {
            val override = record.dedupOverride ?: continue
            if (record.key !in favoriteAt) continue
            val manga = mangaRepository.getMangaByUrlAndSourceId(record.key.url, record.key.sourceId) ?: continue
            dedupSettings.setOverride(manga.id, override.toOverride())
        }
        return favoriteAt
    }

    /** Applies [updates] to the rows that still hold what the snapshot read; returns the updates written. */
    private suspend fun update(
        updates: List<EntryUpdate>,
        categoryLocalIds: Map<String, Long>,
        categoryUuids: Map<Long, String>,
        skipped: MutableSet<EntryKey>,
    ): List<EntryUpdate> {
        if (updates.isEmpty()) return emptyList()
        val current = source.readCurrent(updates.map { it.entry.key }, categoryUuids)
        val live = categoryLocalIds.keys
        val ready = updates.mapNotNull { update ->
            val key = update.entry.key
            val row = current[key]
            if (row != null && source.observed(key)?.matches(row.record, live) == true) {
                Write(update, row)
            } else {
                skipped += key
                null
            }
        }
        if (ready.isEmpty()) return emptyList()

        val now = Clock.System.now()
        for (chunk in ready.mapNotNull { it.mangaUpdate(now) }.chunked(ROW_BATCH)) {
            if (!mangaRepository.updateAll(chunk)) logcat(LogPriority.ERROR, SYNC_LOG_TAG) { "apply manga failed" }
            yield()
        }
        for ((update, row) in ready) {
            if (EntryField.CATEGORIES in update.fields) {
                setMangaCategories.await(row.manga.id, update.entry.categoryUuids.mapNotNull(categoryLocalIds::get))
            }
            if (EntryField.EXCLUDED_SCANLATORS in update.fields) {
                setExcludedScanlators.await(row.manga.id, update.entry.excludedScanlators.toSet())
            }
        }

        val chapterUpdates = ArrayList<ChapterUpdate>()
        val historyWrites = ArrayList<HistoryWrite>()
        for ((update, row) in ready) {
            if (update.chapters.isEmpty()) continue
            val byUrl = row.chapters.associateBy { it.url }
            for (target in update.chapters) {
                // A row deleted since the read stays deleted; the re-read reports the entry unrealized.
                val chapter = byUrl[target.url] ?: continue
                chapterUpdate(chapter, target)?.let(chapterUpdates::add)
                historyWrite(row, chapter, target)?.let(historyWrites::add)
            }
        }
        // One transaction per batch keeps each writer-lock hold well below the driver's busy timeout.
        for (chunk in chapterUpdates.chunked(ROW_BATCH)) {
            chapterRepository.updateAll(chunk)
            yield()
        }
        for (chunk in historyWrites.chunked(ROW_BATCH)) {
            for (write in chunk) {
                when (write) {
                    is HistoryWrite.Upsert -> historyRepository.upsertHistory(write.update)
                    is HistoryWrite.Reset -> historyRepository.resetHistory(write.historyId)
                }
            }
            yield()
        }

        val tracks = ready.flatMap { (update, row) -> update.upsertTracks.map { it.toTrack(row.manga.id) } }
        for (chunk in tracks.chunked(ROW_BATCH)) {
            trackRepository.upsertAll(chunk)
            yield()
        }
        for ((update, row) in ready) {
            for (trackerId in update.deleteTrackerIds) trackRepository.delete(row.manga.id, trackerId)
            if (EntryField.DEDUP_OVERRIDE in update.fields) {
                dedupSettings.setOverride(row.manga.id, update.entry.dedupOverride?.toOverride())
            }
        }
        return ready.map { it.update }
    }

    private data class Write(val update: EntryUpdate, val row: LocalRow) {

        fun mangaUpdate(now: Instant): MangaUpdate? {
            val fields = update.fields
            if (MANGA_FIELDS.none(fields::contains)) return null
            val entry = update.entry
            return MangaUpdate(row.manga.id) {
                if (EntryField.FAVORITE in fields) {
                    favoriteAt = if (entry.favorite) refavoriteAt(entry.favoriteAtMillis, row.chapters, now) else null
                }
                if (EntryField.VIEWER_FLAGS in fields) viewerFlags = entry.viewerFlags
                if (EntryField.CHAPTER_FLAGS in fields) chapterFlags = entry.chapterFlags
                if (EntryField.NOTES in fields) notes = entry.notes
            }
        }
    }

    private sealed interface HistoryWrite {
        class Upsert(val update: HistoryUpdate) : HistoryWrite
        class Reset(val historyId: Long) : HistoryWrite
    }

    private fun historyWrite(row: LocalRow, chapter: Chapter, target: ChapterRecord): HistoryWrite? {
        val history = row.history[chapter.id]
        val read = history?.takeIf { it.readAt != null }
        if (target.historyReadAtMillis == read?.readAt?.toEpochMilliseconds() &&
            target.historyDurationMs == (read?.readDuration ?: 0L)
        ) {
            return null
        }
        val targetReadAt = target.historyReadAtMillis
        return when {
            // The upsert adds to the stored duration (which a cleared row keeps), so write the difference.
            targetReadAt != null -> HistoryWrite.Upsert(
                HistoryUpdate(
                    chapterId = chapter.id,
                    readAt = Instant.fromEpochMilliseconds(targetReadAt),
                    sessionReadDuration = target.historyDurationMs - (history?.readDuration ?: 0L),
                ),
            )
            read != null -> HistoryWrite.Reset(read.id)
            else -> null
        }
    }

    internal companion object {
        /** Entries per restore call; each call is one transaction. */
        const val RESTORE_BATCH = 25

        /** Rows per write transaction, and per batch between yields. */
        const val ROW_BATCH = 2_000

        private val MANGA_FIELDS =
            listOf(EntryField.FAVORITE, EntryField.VIEWER_FLAGS, EntryField.CHAPTER_FLAGS, EntryField.NOTES)
    }
}

/**
 * Favorite time for re-adding an existing row: the merged one if no chapter row was fetched after it (so no
 * existing chapter turns up in Updates), else now. Never earlier than the newest fetch, since `updatesView` lists
 * the chapters fetched strictly after the favorite time.
 */
private fun refavoriteAt(mergedMillis: Long?, chapters: List<Chapter>, now: Instant): Instant {
    val newestFetch = chapters.maxOfOrNull { it.dateFetch }
    val merged = mergedMillis?.let(Instant::fromEpochMilliseconds)
    return when {
        merged != null && (newestFetch == null || merged >= newestFetch) -> merged
        newestFetch != null && newestFetch > now -> newestFetch
        else -> now
    }
}

/** The fields of [chapter] that differ from [target]'s state, or null when none do. */
private fun chapterUpdate(chapter: Chapter, target: ChapterRecord): ChapterUpdate? {
    val lastPageRead = target.lastPageRead.toInt()
    if (chapter.read == target.read && chapter.bookmark == target.bookmark && chapter.lastPageRead == lastPageRead) {
        return null
    }
    return ChapterUpdate(chapter.id) {
        if (chapter.read != target.read) read = target.read
        if (chapter.bookmark != target.bookmark) bookmark = target.bookmark
        if (chapter.lastPageRead != lastPageRead) this.lastPageRead = lastPageRead
    }
}

/** True when [actual] holds everything this update wrote. */
private fun EntryUpdate.isRealizedBy(actual: EntryRecord): Boolean {
    val fieldsHold = fields.all { field ->
        when (field) {
            EntryField.FAVORITE -> actual.favorite == entry.favorite
            EntryField.VIEWER_FLAGS -> actual.viewerFlags == entry.viewerFlags
            EntryField.CHAPTER_FLAGS -> actual.chapterFlags == entry.chapterFlags
            EntryField.NOTES -> actual.notes == entry.notes
            EntryField.CATEGORIES -> actual.categoryUuids == entry.categoryUuids
            EntryField.EXCLUDED_SCANLATORS -> actual.excludedScanlators == entry.excludedScanlators
            EntryField.DEDUP_OVERRIDE -> actual.dedupOverride == entry.dedupOverride
        }
    }
    val rows = actual.chapters.associateBy { it.url }
    val tracks = actual.tracks.associateBy { it.trackerId }
    return fieldsHold &&
        chapters.all { target -> rows[target.url]?.hasStateOf(target) == true } &&
        upsertTracks.all { tracks[it.trackerId] == it } &&
        deleteTrackerIds.none(tracks::containsKey)
}

private fun ChapterRecord.hasStateOf(other: ChapterRecord): Boolean =
    read == other.read &&
        bookmark == other.bookmark &&
        lastPageRead == other.lastPageRead &&
        historyReadAtMillis == other.historyReadAtMillis &&
        historyDurationMs == other.historyDurationMs

/** [this] without placeholder memberships of categories that have no sync uuid; the base never holds them. */
private fun EntryRecord.withoutUnmapped(): EntryRecord {
    if (categoryUuids.none { it.startsWith(UNMAPPED_CATEGORY_PREFIX) }) return this
    return copy(categoryUuids = categoryUuids.filterNot { it.startsWith(UNMAPPED_CATEGORY_PREFIX) })
}

/**
 * [this] as Mihon's restore input: the entry with every index chapter, fetched at [favoriteAt] so that neither
 * Updates nor the next library update sees them as new. Categories without a local id (their creation was
 * skipped) are left out; the re-read then reports the entry unrealized.
 */
private fun EntryRecord.toRestoredManga(favoriteAt: Instant, categoryLocalIds: Map<String, Long>) = RestoredManga(
    manga = Manga.create().copy(
        source = key.sourceId,
        url = key.url,
        favoriteAt = favoriteAt,
        title = title,
        author = author,
        artist = artist,
        description = description,
        genre = genre.takeIf { it.isNotEmpty() },
        status = status,
        thumbnailUrl = thumbnailUrl,
        initialized = initialized,
        updateStrategy = UpdateStrategy.entries.getOrElse(updateStrategy) { UpdateStrategy.ALWAYS_UPDATE },
        viewerFlags = viewerFlags,
        chapterFlags = chapterFlags,
        notes = notes,
        memo = memoJson.toJsonObject(),
    ),
    chapters = chapters.map { it.toChapter(favoriteAt) },
    categoryIds = categoryUuids.mapNotNull(categoryLocalIds::get),
    history = chapters.mapNotNull { chapter ->
        val readAt = chapter.historyReadAtMillis ?: return@mapNotNull null
        RestoredHistory(chapter.url, Instant.fromEpochMilliseconds(readAt), chapter.historyDurationMs)
    },
    tracks = tracks.map { it.toTrack(mangaId = 0) },
    excludedScanlators = excludedScanlators,
)

private fun ChapterRecord.toChapter(dateFetch: Instant) = Chapter.create().copy(
    url = url,
    name = name,
    chapterNumber = chapterNumber,
    scanlator = scanlator,
    sourceOrder = sourceOrder,
    dateUpload = dateUploadMillis?.let(Instant::fromEpochMilliseconds),
    memo = memoJson.toJsonObject(),
    read = read,
    bookmark = bookmark,
    lastPageRead = lastPageRead.toInt(),
    dateFetch = dateFetch,
)

/** The track upsert keys on (manga, tracker), so the row id is irrelevant. */
private fun TrackRecord.toTrack(mangaId: Long) = Track(
    id = 0,
    mangaId = mangaId,
    trackerId = trackerId,
    remoteId = remoteId,
    libraryId = libraryId,
    title = title,
    lastChapterRead = lastChapterRead,
    totalChapters = totalChapters,
    status = status,
    score = score,
    remoteUrl = remoteUrl,
    startDate = startDateMillis?.let(Instant::fromEpochMilliseconds),
    finishDate = finishDateMillis?.let(Instant::fromEpochMilliseconds),
    private = private,
)

private fun DedupOverrideRecord.toOverride() = MangaDedupOverride(
    groupChapters = groupChapters,
    mergeSplitChapters = mergeSplitChapters,
    rankedScanlators = rankedScanlators?.names,
)

private fun String.toJsonObject(): JsonObject {
    if (isEmpty()) return JsonObject.EMPTY
    return runCatching { Json.parseToJsonElement(this).jsonObject }.getOrDefault(JsonObject.EMPTY)
}
