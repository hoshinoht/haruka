package haruka.sync.store

import haruka.sync.model.Bucket
import haruka.sync.model.EntryKey
import haruka.sync.merge.BaseBucket as MergeBase

/*
 * The store keeps each entry's chapters that had NO local row (few), the merge works with the chapters that did
 * (per entry, absent key = none). The merge treats a missing key and an empty set alike, so both directions are
 * lossless for every chapter the record lists.
 */

/** This stored base bucket as the merge engine's input. */
fun BaseBucket.toMergeBase(): MergeBase {
    val present = HashMap<EntryKey, Set<String>>(entries.size * 2)
    for (entry in entries) {
        val chapters = entry.record.chapters
        if (chapters.isEmpty()) continue
        val urls = HashSet<String>(chapters.size * 2)
        for (chapter in chapters) if (entry.isLocalPresent(chapter.url)) urls += chapter.url
        if (urls.isNotEmpty()) present[entry.record.key] = urls
    }
    return MergeBase(Bucket(index, entries.map { it.record }), present)
}

/** The bucket to stage as the new base, from a merge's [merged] bucket and its [localPresent] flags. */
fun baseBucketOf(merged: Bucket, localPresent: Map<EntryKey, Set<String>>): BaseBucket = BaseBucket(
    index = merged.index,
    entries = merged.entries.map { record ->
        val present = localPresent[record.key].orEmpty()
        BaseEntry(record, record.chapters.mapNotNull { it.url.takeUnless(present::contains) })
    },
).canonical()
