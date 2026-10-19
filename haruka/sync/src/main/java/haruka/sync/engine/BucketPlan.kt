package haruka.sync.engine

import haruka.sync.codec.SyncCodec
import haruka.sync.merge.EntryRemoval
import haruka.sync.merge.EntryUpdate
import haruka.sync.merge.MergeConflict
import haruka.sync.model.Bucket
import haruka.sync.model.EntryKey
import haruka.sync.model.EntryRecord
import haruka.sync.store.BaseBucket
import haruka.sync.store.BaseEntry
import kotlinx.serialization.protobuf.ProtoBuf

/**
 * What one bucket's merge leaves for the commit, the apply and the staging, kept compact (gzipped bytes, keys) so a
 * first sync of a large library never holds every merged record as objects at once.
 */
internal class BucketPlan(
    val index: Int,
    /** The merged bucket differs from the remote in synced state. */
    val remoteDirty: Boolean,
    /** The payload to upload; null when the bucket is unchanged remotely or merged to empty. */
    val upload: EncodedPayload?,
    /** The base bucket to stage ([encodeStaged]), or null to carry the current base's file unchanged. */
    val staged: ByteArray?,
    /** The staged records differ from the merged ones (held remote removals keep the pre-merge base entry). */
    val heldRemote: Boolean,
    /** Entries to insert; their records are the staged ones. */
    val insertKeys: List<EntryKey>,
    val updates: List<EntryUpdate>,
    val held: List<EntryRemoval>,
    val conflicts: List<MergeConflict>,
) {
    /** Set from the applier's report. */
    var skipped: Set<EntryKey> = emptySet()
    var unrealized: Map<EntryKey, EntryRecord> = emptyMap()

    val hasLocalChanges: Boolean get() = insertKeys.isNotEmpty() || updates.isNotEmpty()

    /** The staged records no longer equal the committed head's, so the base body needs its own sha. */
    val recordsPatched: Boolean get() = heldRemote || skipped.isNotEmpty() || unrealized.isNotEmpty()
}

private val baseProtoBuf = ProtoBuf { encodeDefaults = false }

internal fun encodeStaged(bucket: BaseBucket): ByteArray =
    SyncCodec.gzip(baseProtoBuf.encodeToByteArray(BaseBucket.serializer(), bucket))

internal fun decodeStaged(bytes: ByteArray): BaseBucket =
    baseProtoBuf.decodeFromByteArray(BaseBucket.serializer(), SyncCodec.gunzip(bytes))

/** Plaintext sha of the records [this] holds, as a payload of them would record it. */
internal fun BaseBucket.recordsSha(): String =
    SyncCodec.sha256Hex(SyncCodec.encodeBucket(Bucket(index, entries.map { it.record })))

/**
 * Replaces the entries of [keys] with their pre-merge base entries ([preMerge], null in union mode), dropping those
 * the base did not have, so the next sync merges them again from the old base.
 */
internal fun BaseBucket.withPreMerge(keys: Set<EntryKey>, preMerge: BaseBucket?): BaseBucket {
    if (keys.isEmpty()) return this
    val before = preMerge?.entries?.associateBy { it.record.key }.orEmpty()
    val kept = entries.filterTo(ArrayList()) { it.record.key !in keys }
    for (key in keys) before[key]?.let(kept::add)
    kept.sortBy { it.record.key }
    return BaseBucket(index, kept)
}

/** Marks every chapter of the inserted entries [keys] as locally present: the applier inserted the full index. */
internal fun BaseBucket.withInserted(keys: Collection<EntryKey>): BaseBucket {
    if (keys.isEmpty()) return this
    val inserted = keys.toHashSet()
    return BaseBucket(
        index,
        entries.map { if (it.record.key in inserted) BaseEntry(it.record) else it },
    )
}

/** Gives the entries of [actual] the state this device really holds after the apply. */
internal fun BaseBucket.withActual(actual: Map<EntryKey, EntryRecord>): BaseBucket {
    if (actual.isEmpty()) return this
    return BaseBucket(index, entries.map { entry -> actual[entry.record.key]?.let(entry::withActual) ?: entry })
}

/**
 * The entry with this device's actual entry-level fields, tracks and chapter states. Chapters without a local row
 * keep their merged state and become absent; source and index metadata stay as merged.
 */
private fun BaseEntry.withActual(actual: EntryRecord): BaseEntry {
    val rows = actual.chapters.associateBy { it.url }
    val chapters = record.chapters.map { chapter ->
        val row = rows[chapter.url] ?: return@map chapter
        chapter.copy(
            read = row.read,
            bookmark = row.bookmark,
            lastPageRead = row.lastPageRead,
            historyReadAtMillis = row.historyReadAtMillis,
            historyDurationMs = row.historyDurationMs,
        )
    }
    return BaseEntry(
        record = record.copy(
            favorite = actual.favorite,
            removedAtMillis = if (actual.favorite) null else record.removedAtMillis,
            viewerFlags = actual.viewerFlags,
            chapterFlags = actual.chapterFlags,
            notes = actual.notes,
            categoryUuids = actual.categoryUuids,
            excludedScanlators = actual.excludedScanlators,
            dedupOverride = actual.dedupOverride,
            tracks = actual.tracks,
            chapters = chapters,
        ),
        absentChapterUrls = record.chapters.mapNotNull { it.url.takeUnless(rows::containsKey) },
    )
}
