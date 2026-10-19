package haruka.sync.merge

import haruka.sync.codec.SyncCodec
import haruka.sync.model.BUCKET_COUNT
import haruka.sync.model.Bucket
import haruka.sync.model.ChapterRecord
import haruka.sync.model.EntryKey
import haruka.sync.model.EntryRecord
import haruka.sync.model.TrackRecord
import haruka.sync.model.bucketOf
import haruka.sync.model.canonical
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.lang.management.ManagementFactory
import kotlin.random.Random

/**
 * Plan step p2-merge/perf: a synthetic 950-entry / 335k-chapter library with 1 % of the chapters changed locally
 * and 1 % remotely (plus 1 % of the entries edited on each side), merged bucket by bucket within 3 s. Per-bucket
 * allocation is reported as the upper bound of the extra heap one bucket merge needs.
 */
class MergeScaleTest {

    @Test
    fun `a full library merges bucket by bucket within the budget`() {
        val random = Random(SEED)
        val library = library(random)
        val base = library.groupBy { bucketOf(it.key) }
        val local = edit(library.map(::localCopy), random).groupBy { bucketOf(it.key) }
        // Decoded like a download, so the remote shares no objects with the base or the local rows.
        val remote = edit(library, random).groupBy { bucketOf(it.key) }.mapValues { (index, entries) ->
            SyncCodec.decodeBucket(SyncCodec.encodeBucket(Bucket(index, entries)))
        }
        val bases = (0..<BUCKET_COUNT).map { index ->
            val entries = base[index].orEmpty()
            val present = entries.associate { e -> e.key to e.chapters.mapTo(HashSet()) { it.url } }
            BaseBucket(Bucket(index, entries).canonical(), present)
        }
        val options = EntryMergeOptions(nowMillis = NOW)
        val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

        var maxBucketBytes = 0L
        var dirty = 0
        var mergedChapters = 0
        var localChapterWrites = 0
        var conflicts = 0
        val start = System.nanoTime()
        for (index in 0..<BUCKET_COUNT) {
            val allocatedBefore = threads.currentThreadAllocatedBytes
            val result = EntryMerge.mergeBucket(index, bases[index], local[index].orEmpty(), remote[index], options)
            maxBucketBytes = maxOf(maxBucketBytes, threads.currentThreadAllocatedBytes - allocatedBefore)
            if (result.remoteDirty) dirty++
            mergedChapters += result.merged.entries.sumOf { it.chapters.size }
            localChapterWrites += result.localChanges.updates.sumOf { it.chapters.size }
            conflicts += result.conflicts.size
        }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000

        println(
            "MergeScaleTest: ${library.size} entries, ${library.sumOf { it.chapters.size }} chapters, " +
                "merge ${elapsedMs}ms, max bucket allocation ${maxBucketBytes / 1024} KiB, dirty buckets $dirty, " +
                "local chapter writes $localChapterWrites, conflicts $conflicts",
        )
        mergedChapters shouldBe CHAPTERS
        // Each side changed about 3,350 chapters spread over the whole library.
        localChapterWrites shouldBeGreaterThan 0
        dirty shouldBeGreaterThan 0
        elapsedMs shouldBeLessThanOrEqual BUDGET_MS
    }

    /** 950 favorites; chapter counts vary per entry and add up to exactly [CHAPTERS]. */
    private fun library(random: Random): List<EntryRecord> {
        val counts = IntArray(ENTRIES) { random.nextInt(5, 700) }
        var missing = CHAPTERS - counts.sum()
        var i = 0
        while (missing != 0) {
            val step = if (missing > 0) 1 else -1
            if (counts[i] + step > 0) {
                counts[i] += step
                missing -= step
            }
            i = (i + 1) % ENTRIES
        }
        return counts.mapIndexed { n, count ->
            // A typical series: read up to some point, the next chapter in progress with history.
            val readUpTo = random.nextInt(0, count + 1)
            EntryRecord(
                key = EntryKey(sourceId = 1L + n % 7, url = "/manga/$n"),
                favorite = true,
                favoriteAtMillis = 1_600_000_000_000L + n,
                title = "Series $n",
                author = "Author ${n % 50}",
                description = "Description of series $n",
                genre = listOf("Action", "Drama"),
                categoryUuids = listOf("category-${n % 5}"),
                chapters = (0..<count).map { c ->
                    ChapterRecord(
                        url = "/manga/$n/chapter/$c",
                        name = "Chapter ${c + 1}",
                        chapterNumber = c + 1.0,
                        scanlator = "Group ${c % 3}",
                        sourceOrder = (count - c).toLong(),
                        dateUploadMillis = 1_500_000_000_000L + c * 86_400_000L,
                        read = c < readUpTo,
                        lastPageRead = if (c == readUpTo) random.nextLong(1, 40) else 0,
                        historyReadAtMillis = if (c == readUpTo - 1 || c == readUpTo) 1_700_000_000_000L + c else null,
                        historyDurationMs = if (c == readUpTo - 1 || c == readUpTo) random.nextLong(1, 600_000) else 0,
                    )
                },
                tracks = if (n % 3 ==
                    0
                ) {
                    listOf(TrackRecord(trackerId = 1, remoteId = n.toLong(), title = "Series $n"))
                } else {
                    emptyList()
                },
            ).canonical()
        }
    }

    /** The same rows as a fresh database read: equal content, distinct objects. */
    private fun localCopy(entry: EntryRecord) = entry.copy(chapters = entry.chapters.map { it.copy() })

    /** Changes 1 % of the chapters' state and 1 % of the entries' settings. */
    private fun edit(entries: List<EntryRecord>, random: Random): List<EntryRecord> {
        val chapterEdits = HashMap<Int, MutableSet<Int>>()
        repeat(CHAPTERS / 100) {
            val n = random.nextInt(entries.size)
            chapterEdits.getOrPut(n) { HashSet() } += random.nextInt(entries[n].chapters.size)
        }
        val entryEdits = (0..<ENTRIES / 100).mapTo(HashSet()) { random.nextInt(entries.size) }
        return entries.mapIndexed { n, entry ->
            val edits = chapterEdits[n]
            var out = if (edits == null) {
                entry
            } else {
                entry.copy(
                    chapters = entry.chapters.mapIndexed { c, chapter ->
                        if (c !in edits) {
                            chapter
                        } else {
                            chapter.copy(
                                read = !chapter.read,
                                lastPageRead = random.nextLong(0, 40),
                                historyReadAtMillis = NOW - random.nextLong(1, 1_000_000),
                                historyDurationMs = chapter.historyDurationMs + random.nextLong(1, 60_000),
                            )
                        }
                    },
                )
            }
            if (n in
                entryEdits
            ) {
                out = out.copy(notes = "edited ${random.nextInt()}", chapterFlags = random.nextLong(1, 16))
            }
            out
        }
    }

    private companion object {
        const val SEED = 950_335L
        const val ENTRIES = 950
        const val CHAPTERS = 335_000
        const val BUDGET_MS = 3_000L
        const val NOW = 1_800_000_000_000L
    }
}
