package haruka.sync.store

import haruka.sync.model.EntryRecord
import haruka.sync.model.canonical
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/*
 * The merge base's own records. They stay on the device and are never uploaded, but a base written by one build is
 * read by the next, so field numbers here never change meaning either.
 */

/** One bucket of the merge base: the applied entries whose `bucketOf` is [index], sorted by key. */
@Serializable
data class BaseBucket(
    @ProtoNumber(1) val index: Int,
    @ProtoNumber(2) val entries: List<BaseEntry> = emptyList(),
)

/**
 * An applied entry plus its chapters' `localPresent` flags, which the remote format does not carry. A chapter of
 * [record] is `localPresent` unless its url is in [absentChapterUrls]: no local row was observed when the base was
 * written (an UNKNOWN chapter, a remote-only chapter of an existing entry, or any chapter of an entry whose row is
 * missing locally).
 */
@Serializable
data class BaseEntry(
    @ProtoNumber(1) val record: EntryRecord,
    /** Sorted and unique; the store writes and returns the canonical form. */
    @ProtoNumber(2) val absentChapterUrls: List<String> = emptyList(),
) {
    fun isLocalPresent(chapterUrl: String): Boolean = absentChapterUrls.binarySearch(chapterUrl) < 0
}

/** Entries sorted by key (a repeated key is a caller bug), records canonical, absent urls sorted and unique. */
internal fun BaseBucket.canonical(): BaseBucket {
    val sorted = entries
        .map { BaseEntry(it.record.canonical(), it.absentChapterUrls.toSortedSet().toList()) }
        .sortedBy { it.record.key }
    for (i in 1..<sorted.size) {
        require(sorted[i - 1].record.key != sorted[i].record.key) { "Duplicate entry key: ${sorted[i].record.key}" }
    }
    return BaseBucket(index, sorted)
}
