package haruka.sync.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/*
 * Haruka's sync format (fork-owned, not Mihon's Backup* models). Field numbers are the wire contract: never reuse
 * or renumber them; incompatible changes bump [FORMAT_VERSION]. Defaults are omitted on the wire
 * (encodeDefaults = false), so untouched chapter state costs nothing. Lists that model sets or keyed collections
 * are kept sorted and unique by [canonical]; the codec encodes the canonical form only.
 */

/** Identity of a library entry across devices; Mihon's `manga` table is unique on (source, url). */
@Serializable
data class EntryKey(
    @ProtoNumber(1) val sourceId: Long,
    @ProtoNumber(2) val url: String,
) : Comparable<EntryKey> {
    override fun compareTo(other: EntryKey): Int {
        val bySource = sourceId.compareTo(other.sourceId)
        return if (bySource != 0) bySource else url.compareTo(other.url)
    }
}

/**
 * One library entry. Source metadata (title through [memoJson]) is used only to insert an entry missing on a
 * device and is never pushed onto an existing local row, so it is left out of the entry digest.
 */
@Serializable
data class EntryRecord(
    @ProtoNumber(1) val key: EntryKey,
    @ProtoNumber(2) val favorite: Boolean = false,
    /** Null when unknown; Mihon's `UNKNOWN_FAVORITE_AT` (0) is mapped to null by the app glue. */
    @ProtoNumber(3) val favoriteAtMillis: Long? = null,
    /** Tombstone time; set with favorite = false and pruned 180 days later. */
    @ProtoNumber(4) val removedAtMillis: Long? = null,
    @ProtoNumber(5) val title: String = "",
    @ProtoNumber(6) val author: String? = null,
    @ProtoNumber(7) val artist: String? = null,
    @ProtoNumber(8) val description: String? = null,
    /** Source order, not a set. */
    @ProtoNumber(9) val genre: List<String> = emptyList(),
    @ProtoNumber(10) val status: Long = 0,
    @ProtoNumber(11) val thumbnailUrl: String? = null,
    @ProtoNumber(12) val initialized: Boolean = false,
    /** `UpdateStrategy` ordinal. */
    @ProtoNumber(13) val updateStrategy: Int = 0,
    /** Mihon's manga `memo` JSON object as text; empty means none. */
    @ProtoNumber(14) val memoJson: String = "",
    @ProtoNumber(15) val viewerFlags: Long = 0,
    @ProtoNumber(16) val chapterFlags: Long = 0,
    @ProtoNumber(17) val notes: String = "",
    /** Set, sorted. */
    @ProtoNumber(18) val categoryUuids: List<String> = emptyList(),
    /** Set, sorted. */
    @ProtoNumber(19) val excludedScanlators: List<String> = emptyList(),
    @ProtoNumber(20) val dedupOverride: DedupOverrideRecord? = null,
    /** The entry's full chapter index, sorted by url. */
    @ProtoNumber(21) val chapters: List<ChapterRecord> = emptyList(),
    /** Sorted by tracker id. */
    @ProtoNumber(22) val tracks: List<TrackRecord> = emptyList(),
)

/**
 * A chapter: index metadata ([url] through [memoJson]), carried for every chapter so a device can insert an entry
 * it lacks, plus synced state. History duration is meaningless without a read time and is normalized to 0 by
 * [canonical] whenever [historyReadAtMillis] is null.
 */
@Serializable
data class ChapterRecord(
    @ProtoNumber(1) val url: String,
    @ProtoNumber(2) val name: String = "",
    @ProtoNumber(3) val chapterNumber: Double = 0.0,
    @ProtoNumber(4) val scanlator: String? = null,
    @ProtoNumber(5) val sourceOrder: Long = 0,
    @ProtoNumber(6) val dateUploadMillis: Long? = null,
    /** Mihon's chapter `memo` JSON object as text; empty means none. */
    @ProtoNumber(7) val memoJson: String = "",
    @ProtoNumber(8) val read: Boolean = false,
    @ProtoNumber(9) val bookmark: Boolean = false,
    @ProtoNumber(10) val lastPageRead: Long = 0,
    /** Null = no history or history cleared. */
    @ProtoNumber(11) val historyReadAtMillis: Long? = null,
    @ProtoNumber(12) val historyDurationMs: Long = 0,
) {
    /** True when no synced state is set; such chapters are index-only and never affect the entry digest. */
    val hasDefaultState: Boolean
        get() = !read && !bookmark && lastPageRead == 0L && historyReadAtMillis == null && historyDurationMs == 0L
}

/** A tracker link, keyed by (entry, [trackerId]). Credentials are never synced. */
@Serializable
data class TrackRecord(
    @ProtoNumber(1) val trackerId: Long,
    @ProtoNumber(2) val remoteId: Long = 0,
    @ProtoNumber(3) val libraryId: Long? = null,
    @ProtoNumber(4) val title: String = "",
    @ProtoNumber(5) val lastChapterRead: Double = 0.0,
    @ProtoNumber(6) val totalChapters: Long = 0,
    @ProtoNumber(7) val status: Long = 0,
    @ProtoNumber(8) val score: Double = 0.0,
    @ProtoNumber(9) val remoteUrl: String = "",
    @ProtoNumber(10) val startDateMillis: Long? = null,
    @ProtoNumber(11) val finishDateMillis: Long? = null,
    @ProtoNumber(12) val private: Boolean = false,
)

/** Haruka's per-entry dedup override (`MangaDedupOverride`); null fields follow the global setting. */
@Serializable
data class DedupOverrideRecord(
    @ProtoNumber(1) val groupChapters: Boolean? = null,
    @ProtoNumber(2) val mergeSplitChapters: Boolean? = null,
    @ProtoNumber(3) val rankedScanlators: ScanlatorRanking? = null,
)

/** Wraps the ranking so "no override" (null) and "empty ranking" stay distinct on the wire. Best first. */
@Serializable
data class ScanlatorRanking(
    @ProtoNumber(1) val names: List<String> = emptyList(),
)

/** A category, identified by a fork-owned uuid mapped to the local category id on each device. */
@Serializable
data class CategoryRecord(
    @ProtoNumber(1) val uuid: String,
    @ProtoNumber(2) val name: String = "",
    @ProtoNumber(3) val order: Long = 0,
    @ProtoNumber(4) val flags: Long = 0,
    /** Tombstone time; null while the category exists. */
    @ProtoNumber(5) val deletedAtMillis: Long? = null,
)

/** A whitelisted fork preference in its stored string form; an absent record means the preference is unset. */
@Serializable
data class PrefRecord(
    @ProtoNumber(1) val key: String,
    @ProtoNumber(2) val value: String = "",
)

/** Payload of one bucket file: the entries whose [bucketOf] is [index], sorted by key. */
@Serializable
data class Bucket(
    @ProtoNumber(1) val index: Int,
    @ProtoNumber(2) val entries: List<EntryRecord> = emptyList(),
)

/** Payload of the categories file, sorted by uuid. */
@Serializable
data class CategoriesPayload(
    @ProtoNumber(1) val categories: List<CategoryRecord> = emptyList(),
)

/** Payload of the prefs file, sorted by key. */
@Serializable
data class PrefsPayload(
    @ProtoNumber(1) val prefs: List<PrefRecord> = emptyList(),
)
