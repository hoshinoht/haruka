package haruka.sync.merge

import haruka.sync.codec.SyncCodec
import haruka.sync.model.Bucket
import haruka.sync.model.ChapterRecord
import haruka.sync.model.DedupOverrideRecord
import haruka.sync.model.EntryKey
import haruka.sync.model.EntryRecord
import haruka.sync.model.TrackRecord
import haruka.sync.model.canonical
import java.util.EnumSet

/**
 * The base's copy of one bucket: the entries as last applied, plus per entry the urls of the chapters whose
 * `localPresent` flag is set, i.e. that had a local row when the base was recorded.
 */
data class BaseBucket(
    val bucket: Bucket,
    val localPresent: Map<EntryKey, Set<String>> = emptyMap(),
)

data class EntryMergeOptions(
    /** Stamps new tombstones and prunes those older than [TOMBSTONE_RETENTION_MILLIS]. */
    val nowMillis: Long,
    /** Collapsed category uuid to surviving uuid ([CategoryMergeResult.remap]); applied to every side first. */
    val categoryRemap: Map<String, String> = emptyMap(),
    /** Deleted categories ([CategoryMergeResult.deleted]); memberships in them are dropped from the merge. */
    val deletedCategories: Set<String> = emptySet(),
    /** Mass-removal guard: keep locally originated removals out of the commit. */
    val holdLocalRemovals: Boolean = false,
    /** Mass-removal guard: commit remotely originated removals but don't apply them locally. */
    val holdRemoteRemovals: Boolean = false,
    /** Removals the user confirmed; never held. */
    val confirmedRemovals: Set<EntryKey> = emptySet(),
)

enum class RemovalOrigin {
    /** Removed on this device; the commit would unfavorite it remotely. */
    LOCAL,

    /** Removed elsewhere; applying the merge would unfavorite it on this device. */
    REMOTE,
}

data class EntryRemoval(val key: EntryKey, val origin: RemovalOrigin)

/** Entry-level fields of an [EntryUpdate] that differ from the local row. */
enum class EntryField {
    /** Apply [EntryRecord.favorite]; when it becomes true, [EntryRecord.favoriteAtMillis] is the value to use. */
    FAVORITE,
    VIEWER_FLAGS,
    CHAPTER_FLAGS,
    NOTES,
    CATEGORIES,
    EXCLUDED_SCANLATORS,
    DEDUP_OVERRIDE,
}

/** What to write to an existing local entry so it matches the merge. */
data class EntryUpdate(
    /** The merged entry as this device should hold it (held removals keep the local favorite). */
    val entry: EntryRecord,
    val fields: Set<EntryField>,
    /** Merged state for existing local chapter rows whose state differs; sync never adds or deletes rows here. */
    val chapters: List<ChapterRecord>,
    /** Tracks to insert or overwrite. */
    val upsertTracks: List<TrackRecord>,
    val deleteTrackerIds: List<Long>,
)

data class LocalChangeSet(
    /** Favorites with no local row; inserted with their full chapter index. */
    val inserts: List<EntryRecord> = emptyList(),
    val updates: List<EntryUpdate> = emptyList(),
) {
    val isEmpty: Boolean get() = inserts.isEmpty() && updates.isEmpty()
}

data class EntryMergeResult(
    /**
     * The bucket to commit and to stage as the new base. When the merge changes no synced state of the remote
     * bucket this is the remote bucket itself, so local index-only chapter additions wait for the next upload.
     */
    val merged: Bucket,
    /** True when [merged] differs from the remote bucket in synced state and must be uploaded. */
    val remoteDirty: Boolean,
    /**
     * Per merged entry with a local row, the urls of the chapters observed locally: the new base's `localPresent`
     * flags (false for UNKNOWN chapters). Rows the apply step inserts must be patched in after the apply.
     */
    val localPresent: Map<EntryKey, Set<String>>,
    val localChanges: LocalChangeSet,
    /** Removals this merge carries out, counted by the caller for the mass-removal guard. */
    val removals: List<EntryRemoval>,
    /**
     * Removals the guard held back. A held LOCAL removal stays favorite in [merged]; a held REMOTE removal is in
     * [merged] but not in [localChanges], and the caller stages the pre-merge base entry for it.
     */
    val heldRemovals: List<EntryRemoval>,
    val conflicts: List<MergeConflict>,
)

/** Mass-removal guard threshold: more than 25 entries or more than 10 % of the library in one direction. */
fun isMassRemoval(removals: Int, librarySize: Int): Boolean = removals > 25 || removals * 10L > librarySize

/**
 * Three-way merge of library entries, chapter state, history and tracks, one bucket at a time (plan section
 * "Merge rules").
 *
 * The mass-removal guard needs library-wide counts: merge every bucket with the hold flags off, sum
 * [EntryMergeResult.removals] per origin, and re-merge the buckets with removals with the matching hold flag when
 * [isMassRemoval] says so.
 */
object EntryMerge {

    /**
     * @param base the base bucket, or null in union mode (no usable base).
     * @param local this device's rows in bucket [index], library or not, each with the chapter rows observed.
     * @param remote the remote bucket, or null when the remote has none.
     */
    fun mergeBucket(
        index: Int,
        base: BaseBucket?,
        local: List<EntryRecord>,
        remote: Bucket?,
        options: EntryMergeOptions,
    ): EntryMergeResult {
        require(remote == null || remote.index == index) { "Remote bucket ${remote?.index} merged as $index" }
        val baseByKey = base?.bucket?.entries?.associateBy { it.key }.orEmpty()
        val localByKey = local.associateBy { it.key }
        val remoteByKey = remote?.entries?.associateBy { it.key }.orEmpty()
        val keys = sortedSetOf<EntryKey>().apply {
            addAll(baseByKey.keys)
            addAll(localByKey.keys)
            addAll(remoteByKey.keys)
        }
        val merger = BucketMerger(options)
        for (key in keys) {
            val b = baseByKey[key]
            merger.mergeEntry(
                key = key,
                b = b,
                l = localByKey[key],
                r = remoteByKey[key],
                present = if (b != null) base?.localPresent?.get(key).orEmpty() else emptySet(),
            )
        }
        val remoteBucket = remote?.canonical() ?: Bucket(index)
        val dirty = differsInState(merger.merged, remoteBucket.entries)
        return EntryMergeResult(
            merged = if (dirty) Bucket(index, merger.merged) else remoteBucket,
            remoteDirty = dirty,
            localPresent = merger.localPresent,
            localChanges = LocalChangeSet(merger.inserts, merger.updates),
            removals = merger.removals,
            heldRemovals = merger.held,
            conflicts = merger.conflicts,
        )
    }

    private fun differsInState(merged: List<EntryRecord>, remote: List<EntryRecord>): Boolean {
        if (merged.size != remote.size) return true
        for (i in merged.indices) {
            val m = merged[i]
            val r = remote[i]
            if (m.key != r.key) return true
            if (m != r && SyncCodec.entryDigest(m) != SyncCodec.entryDigest(r)) return true
        }
        return false
    }
}

private const val UNKNOWN_FAVORITE_AT = 0L

/** State-only stand-in for a chapter nobody changed from its defaults; its index fields are never read. */
private val DEFAULT_CHAPTER = ChapterRecord(url = "")

private val NO_OVERRIDE = DedupOverrideRecord()

private class BucketMerger(private val options: EntryMergeOptions) {
    val merged = ArrayList<EntryRecord>()
    val localPresent = HashMap<EntryKey, Set<String>>()
    val inserts = ArrayList<EntryRecord>()
    val updates = ArrayList<EntryUpdate>()
    val removals = ArrayList<EntryRemoval>()
    val held = ArrayList<EntryRemoval>()
    val conflicts = ArrayList<MergeConflict>()

    fun mergeEntry(key: EntryKey, b: EntryRecord?, l: EntryRecord?, r: EntryRecord?, present: Set<String>) {
        // A local row outside the library that neither the base nor the remote knows has nothing to sync.
        if (b == null && r == null && (l == null || !l.favorite)) return

        val base = (b ?: EntryRecord(key)).prepared()
        val local = l?.prepared()
        // A missing side removed the entry: favorite false, everything else as in the base. For a missing local
        // row that leaves every chapter UNKNOWN; a missing remote record is a pruned remote tombstone.
        val lv = local ?: base.copy(favorite = false)
        val rv = r?.prepared() ?: base.copy(favorite = false)
        val subject = "entry ${key.sourceId} ${key.url}"

        var favorite = merge3(base.favorite, lv.favorite, rv.favorite) { lv.favorite }
        val origin = when {
            favorite -> null
            rv.favorite -> RemovalOrigin.LOCAL
            lv.favorite -> RemovalOrigin.REMOTE
            else -> null
        }
        val hold = origin != null &&
            key !in options.confirmedRemovals &&
            if (origin == RemovalOrigin.LOCAL) options.holdLocalRemovals else options.holdRemoteRemovals
        if (origin != null) (if (hold) held else removals).add(EntryRemoval(key, origin))
        // A held local removal never reaches the remote, which keeps the entry as it is.
        if (hold && origin == RemovalOrigin.LOCAL) favorite = true

        val removedAt = if (favorite) {
            null
        } else {
            maxOfNullable(maxOfNullable(b.tombstoneTime(), l.tombstoneTime()), r.tombstoneTime()) ?: options.nowMillis
        }

        // Source metadata is insertion-only: the remote's copy wins, then the base's, then this device's.
        val entry = (r ?: b ?: l!!).copy(
            favorite = favorite,
            favoriteAtMillis = minKnown(minKnown(b?.favoriteAtMillis, l?.favoriteAtMillis), r?.favoriteAtMillis),
            removedAtMillis = removedAt,
            viewerFlags = setting(subject, "viewerFlags", base.viewerFlags, lv.viewerFlags, rv.viewerFlags),
            chapterFlags = setting(subject, "chapterFlags", base.chapterFlags, lv.chapterFlags, rv.chapterFlags),
            notes = setting(subject, "notes", base.notes, lv.notes, rv.notes),
            categoryUuids = mergeSet(base.categoryUuids, lv.categoryUuids, rv.categoryUuids)
                .filterNot { it in options.deletedCategories },
            excludedScanlators = mergeSet(base.excludedScanlators, lv.excludedScanlators, rv.excludedScanlators),
            dedupOverride = mergeDedup(subject, base.dedupOverride, lv.dedupOverride, rv.dedupOverride),
            chapters = mergeChapters(base, local, rv, present),
            tracks = mergeTracks(subject, base.tracks, lv.tracks, rv.tracks),
        ).canonical()

        val expired = removedAt != null && isExpired(removedAt, options.nowMillis)
        if (!expired) {
            merged += entry
            if (l != null) localPresent[key] = l.chapters.mapTo(HashSet(l.chapters.size * 2)) { it.url }
        }

        // Whatever the guard held, this device's favorite stays as it is.
        val target = if (hold) entry.copy(favorite = lv.favorite) else entry
        if (l == null) {
            if (target.favorite) inserts += target
        } else {
            diffLocal(l.canonical(), target)?.let(updates::add)
        }
    }

    private fun EntryRecord.prepared(): EntryRecord {
        val c = canonical()
        val remap = options.categoryRemap
        if (remap.isEmpty() || c.categoryUuids.none { it in remap }) return c
        return c.copy(categoryUuids = c.categoryUuids.map { remap[it] ?: it }).canonical()
    }

    private fun <T> setting(subject: String, field: String, base: T, local: T, remote: T): T =
        conflicts.pickSetting(subject, field, base, local, remote)

    private fun mergeDedup(
        subject: String,
        base: DedupOverrideRecord?,
        local: DedupOverrideRecord?,
        remote: DedupOverrideRecord?,
    ): DedupOverrideRecord? {
        if (local == remote || remote == base) return local
        if (local == base) return remote
        val b = base ?: NO_OVERRIDE
        val l = local ?: NO_OVERRIDE
        val r = remote ?: NO_OVERRIDE
        return DedupOverrideRecord(
            groupChapters = setting(subject, "dedup.groupChapters", b.groupChapters, l.groupChapters, r.groupChapters),
            mergeSplitChapters = setting(
                subject,
                "dedup.mergeSplitChapters",
                b.mergeSplitChapters,
                l.mergeSplitChapters,
                r.mergeSplitChapters,
            ),
            rankedScanlators = setting(
                subject,
                "dedup.rankedScanlators",
                b.rankedScanlators,
                l.rankedScanlators,
                r.rankedScanlators,
            ),
        ).takeUnless { it == NO_OVERRIDE }
    }

    /**
     * Chapter index = union by url. Presence decides the local side: a row the base saw locally is merged three-way;
     * a row the base didn't see locally (NEW-LOCAL) merges against defaults so remote progress lands on it; a
     * chapter without a local row (UNKNOWN, or never local) counts as unchanged locally and so never changes the
     * remote. A url missing from the remote counts as unchanged remotely.
     */
    private fun mergeChapters(
        base: EntryRecord,
        local: EntryRecord?,
        remote: EntryRecord,
        present: Set<String>,
    ): List<ChapterRecord> {
        val b = base.chapters.associateBy { it.url }
        val l = local?.chapters?.associateBy { it.url }.orEmpty()
        val r = remote.chapters.associateBy { it.url }
        val urls = sortedSetOf<String>().apply {
            addAll(b.keys)
            addAll(l.keys)
            addAll(r.keys)
        }
        val out = ArrayList<ChapterRecord>(urls.size)
        for (url in urls) {
            val bc = b[url]
            val lc = l[url]
            val rc = r[url]
            val known = bc ?: DEFAULT_CHAPTER
            val baseState = when {
                lc == null -> known
                bc != null && url in present -> bc
                else -> DEFAULT_CHAPTER
            }
            // Index metadata: the remote's copy is kept, local metadata only fills urls the remote lacks.
            out += mergeChapterState(rc ?: bc ?: lc!!, baseState, lc ?: known, rc ?: known)
        }
        return out
    }

    private fun mergeChapterState(
        metadata: ChapterRecord,
        b: ChapterRecord,
        l: ChapterRecord,
        r: ChapterRecord,
    ): ChapterRecord {
        // Booleans changed on both sides are equal, so their conflict branches are unreachable.
        val read = merge3(b.read, l.read, r.read) { l.read }
        val bookmark = merge3(b.bookmark, l.bookmark, r.bookmark) { l.bookmark }
        val lastPageRead = merge3(b.lastPageRead, l.lastPageRead, r.lastPageRead) {
            maxOf(l.lastPageRead, r.lastPageRead)
        }
        // History (readAt, duration) is one register: Mihon writes both on every read, often with the duration
        // unchanged. A one-sided clear (null, 0) propagates, a re-read beats a concurrent clear and keeps its own
        // duration, and two re-reads take the later time and the longer duration.
        val readAt = merge3(b.historyReadAtMillis, l.historyReadAtMillis, r.historyReadAtMillis) {
            maxOfNullable(l.historyReadAtMillis, r.historyReadAtMillis)
        }
        val duration = when {
            readAt == null -> 0L
            l.historyReadAtMillis == null -> r.historyDurationMs
            r.historyReadAtMillis == null -> l.historyDurationMs
            else -> merge3(b.historyDurationMs, l.historyDurationMs, r.historyDurationMs) {
                maxOf(l.historyDurationMs, r.historyDurationMs)
            }
        }
        if (
            metadata.read == read &&
            metadata.bookmark == bookmark &&
            metadata.lastPageRead == lastPageRead &&
            metadata.historyReadAtMillis == readAt &&
            metadata.historyDurationMs == duration
        ) {
            return metadata
        }
        return metadata.copy(
            read = read,
            bookmark = bookmark,
            lastPageRead = lastPageRead,
            historyReadAtMillis = readAt,
            historyDurationMs = duration,
        )
    }

    /** A track removed on one side stays removed unless the other side changed it. */
    private fun mergeTracks(
        subject: String,
        base: List<TrackRecord>,
        local: List<TrackRecord>,
        remote: List<TrackRecord>,
    ): List<TrackRecord> {
        if (local == remote || remote == base) return local
        if (local == base) return remote
        val b = base.associateBy { it.trackerId }
        val l = local.associateBy { it.trackerId }
        val r = remote.associateBy { it.trackerId }
        val ids = sortedSetOf<Long>().apply {
            addAll(b.keys)
            addAll(l.keys)
            addAll(r.keys)
        }
        return ids.mapNotNull { id ->
            val bt = b[id]
            val lt = l[id]
            val rt = r[id]
            when {
                bt == null && lt == null -> rt
                bt == null && rt == null -> lt
                bt == null -> mergeTrack(subject, TrackRecord(id), lt!!, rt!!)
                lt == null -> rt.takeUnless { it == bt }
                rt == null -> lt.takeUnless { it == bt }
                else -> mergeTrack(subject, bt, lt, rt)
            }
        }
    }

    private fun mergeTrack(subject: String, b: TrackRecord, l: TrackRecord, r: TrackRecord): TrackRecord {
        val p = "track ${b.trackerId} "
        return TrackRecord(
            trackerId = b.trackerId,
            remoteId = setting(subject, p + "remoteId", b.remoteId, l.remoteId, r.remoteId),
            libraryId = setting(subject, p + "libraryId", b.libraryId, l.libraryId, r.libraryId),
            title = setting(subject, p + "title", b.title, l.title, r.title),
            lastChapterRead = merge3(b.lastChapterRead, l.lastChapterRead, r.lastChapterRead) {
                maxOf(l.lastChapterRead, r.lastChapterRead)
            },
            totalChapters = merge3(b.totalChapters, l.totalChapters, r.totalChapters) {
                maxOf(l.totalChapters, r.totalChapters)
            },
            status = setting(subject, p + "status", b.status, l.status, r.status),
            score = setting(subject, p + "score", b.score, l.score, r.score),
            remoteUrl = setting(subject, p + "remoteUrl", b.remoteUrl, l.remoteUrl, r.remoteUrl),
            startDateMillis = setting(
                subject,
                p + "startDate",
                b.startDateMillis,
                l.startDateMillis,
                r.startDateMillis,
            ),
            finishDateMillis = setting(
                subject,
                p + "finishDate",
                b.finishDateMillis,
                l.finishDateMillis,
                r.finishDateMillis,
            ),
            private = setting(subject, p + "private", b.private, l.private, r.private),
        )
    }

    private fun diffLocal(local: EntryRecord, target: EntryRecord): EntryUpdate? {
        val fields = EnumSet.noneOf(EntryField::class.java)
        if (local.favorite != target.favorite) fields += EntryField.FAVORITE
        if (local.viewerFlags != target.viewerFlags) fields += EntryField.VIEWER_FLAGS
        if (local.chapterFlags != target.chapterFlags) fields += EntryField.CHAPTER_FLAGS
        if (local.notes != target.notes) fields += EntryField.NOTES
        if (local.categoryUuids != target.categoryUuids) fields += EntryField.CATEGORIES
        if (local.excludedScanlators != target.excludedScanlators) fields += EntryField.EXCLUDED_SCANLATORS
        if (local.dedupOverride != target.dedupOverride) fields += EntryField.DEDUP_OVERRIDE

        // Both lists are sorted by url and the merged index contains every local url.
        val chapters = ArrayList<ChapterRecord>()
        var t = 0
        for (lc in local.chapters) {
            while (target.chapters[t].url != lc.url) t++
            val tc = target.chapters[t]
            if (!sameState(lc, tc)) chapters += tc
        }

        val localTracks = local.tracks.associateBy { it.trackerId }
        val upsertTracks = target.tracks.filter { localTracks[it.trackerId] != it }
        val targetIds = target.tracks.mapTo(HashSet()) { it.trackerId }
        val deleteTrackerIds = local.tracks.mapNotNull { it.trackerId.takeUnless(targetIds::contains) }

        if (fields.isEmpty() && chapters.isEmpty() && upsertTracks.isEmpty() && deleteTrackerIds.isEmpty()) return null
        return EntryUpdate(target, fields, chapters, upsertTracks, deleteTrackerIds)
    }
}

private fun sameState(a: ChapterRecord, b: ChapterRecord): Boolean =
    a.read == b.read &&
        a.bookmark == b.bookmark &&
        a.lastPageRead == b.lastPageRead &&
        a.historyReadAtMillis == b.historyReadAtMillis &&
        a.historyDurationMs == b.historyDurationMs

/** Time of the entry's tombstone, or null when it is a favorite (a stale removedAt is ignored) or absent. */
private fun EntryRecord?.tombstoneTime(): Long? = this?.takeUnless { it.favorite }?.removedAtMillis

/** Min-register over known favorite times; Mihon's `UNKNOWN_FAVORITE_AT` (0) never wins. */
private fun minKnown(a: Long?, b: Long?): Long? {
    val x = a?.takeUnless { it == UNKNOWN_FAVORITE_AT }
    val y = b?.takeUnless { it == UNKNOWN_FAVORITE_AT }
    return when {
        x == null -> y
        y == null -> x
        else -> minOf(x, y)
    }
}
