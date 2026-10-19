package haruka.sync.merge

import haruka.sync.codec.SyncCodec
import haruka.sync.merge.MergeArbs.HistoryAction
import haruka.sync.merge.MergeArbs.INDEX
import haruka.sync.merge.MergeArbs.NOW
import haruka.sync.merge.MergeArbs.ThreeWay
import haruka.sync.merge.MergeArbs.check
import haruka.sync.model.Bucket
import haruka.sync.model.ChapterRecord
import haruka.sync.model.DedupOverrideRecord
import haruka.sync.model.EntryKey
import haruka.sync.model.EntryRecord
import haruka.sync.model.TrackRecord
import haruka.sync.model.canonical
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.random.Random

/** P1–P9 of plan section "Test properties" for [EntryMerge]; P8 adds merge-level checks to SyncCodecPropertyTest. */
class EntryMergePropertiesTest {

    @Test
    fun `P1 merging the base with itself changes nothing`() = check("P1 entries no-op", MergeArbs.synced) { s ->
        val result = mergeBucket(s.base, s.localRows, s.base.bucket)

        result.remoteDirty shouldBe false
        result.merged shouldBe s.base.bucket
        result.localChanges.isEmpty shouldBe true
        result.localPresent shouldBe s.base.localPresent
        result.removals.shouldBeEmpty()
        result.heldRemovals.shouldBeEmpty()
        result.conflicts.shouldBeEmpty()
    }

    @Test
    fun `P2 a change made only on this device is kept`() = check("P2 entries local-only", MergeArbs.localOnly) { s ->
        val result = merge(s)
        val base = s.base!!.bucket.entries.associateBy { it.key }
        val local = s.local.associateBy { it.key }
        val merged = result.merged.entries.associateBy { it.key }

        result.localChanges.isEmpty shouldBe true
        result.conflicts.shouldBeEmpty()
        merged.keys shouldBe base.keys + s.local.filter { it.favorite && it.key !in base }.map { it.key }
        for ((key, m) in merged) {
            val b = base[key]
            val l = local[key]
            val expected = if (l == null) {
                b!!.synced().copy(favorite = false)
            } else {
                // Chapters without a local row (UNKNOWN or index-only here) keep the base state.
                val observed = l.urls()
                l.synced().copy(chapters = b?.states().orEmpty().filterKeys { it !in observed } + l.states())
            }
            m.synced() shouldBe expected
            m.favoriteAtMillis shouldBe listOfNotNull(b?.favoriteAtMillis, l?.favoriteAtMillis).minOrNull()
            val removedAt = if (m.favorite) null else b?.takeUnless { it.favorite }?.removedAtMillis ?: NOW
            m.removedAtMillis shouldBe removedAt
        }
        result.removals.toSet() shouldBe base.values
            .filter { it.favorite && !merged.getValue(it.key).favorite }
            .mapTo(HashSet()) { EntryRemoval(it.key, RemovalOrigin.LOCAL) }
    }

    @Test
    fun `P2 a change made only on the remote is taken`() = check("P2 entries remote-only", MergeArbs.remoteOnly) { s ->
        val result = mergeBucket(s.synced.base, s.synced.localRows, s.remote)

        result.remoteDirty shouldBe false
        result.merged shouldBe s.remote
        result.conflicts.shouldBeEmpty()
        // Applying the change set makes every local row match the remote; remote favorites missing here are inserted.
        val applied = applyLocal(s.synced.localRows, result.localChanges)
        applied.keys shouldBe s.synced.localRows.map { it.key }.toSet() +
            s.remote.entries.filter { it.favorite }.map { it.key }
        for (r in s.remote.entries) {
            val row = applied[r.key] ?: continue
            val observed = row.urls()
            row.synced() shouldBe r.synced().copy(chapters = r.states().filterKeys { it in observed })
        }
    }

    @Test
    fun `P3 merging is symmetric outside the settings the syncing device wins`() =
        check("P3 entries symmetry", MergeArbs.symmetric) { s ->
            val ab = mergeBucket(s.base, s.a, Bucket(INDEX, s.b).canonical())
            val ba = mergeBucket(s.base, s.b, Bucket(INDEX, s.a).canonical())

            ab.merged.entries.map { it.symmetricView() } shouldBe ba.merged.entries.map { it.symmetricView() }
            ab.conflicts.toSet() shouldBe ba.conflicts.toSet()
            ab.removals.toSet() shouldBe ba.removals.mapTo(HashSet()) { it.copy(origin = it.origin.flipped()) }
        }

    @Test
    fun `P4 merging is idempotent in every field`() = check("P4 entries idempotence", MergeArbs.twoSided) { s ->
        val m = merge(s).merged

        // This device applied m and merges again against the same remote (a crash before the commit, replayed).
        val again = mergeBucket(s.base, m.entries, s.remote)
        again.merged shouldBe m
        again.localChanges.isEmpty shouldBe true

        // m committed and staged as the base everywhere.
        val settled = mergeBucket(BaseBucket(m, m.entries.associate { it.key to it.urls() }), m.entries, m)
        settled.merged shouldBe m
        settled.remoteDirty shouldBe false
        settled.localChanges.isEmpty shouldBe true
        settled.removals.shouldBeEmpty()
        settled.conflicts.shouldBeEmpty()
    }

    @Test
    fun `P5 union mode never regresses either side`() = check("P5 entries union", MergeArbs.union) { s ->
        val result = merge(s)
        val merged = result.merged.entries.associateBy { it.key }
        val remote = s.remote?.entries.orEmpty().associateBy { it.key }

        result.removals.shouldBeEmpty()
        result.heldRemovals.shouldBeEmpty()
        for (side in listOf(s.local, remote.values)) {
            for (e in side) {
                // A local row outside the library that the remote doesn't know has nothing to sync.
                if (e.key !in remote && !e.favorite) continue
                val m = merged[e.key] ?: throw AssertionError("${e.key} lost")
                m.shouldNotRegressFrom(e)
            }
        }
    }

    @Test
    fun `P6 the change set never deletes chapter rows or adds rows to an existing entry`() =
        check("P6 entries rows", MergeArbs.anyMerge) { s ->
            val result = merge(s)
            val local = s.local.associateBy { it.key }
            val changes = result.localChanges
            val changedKeys = changes.inserts.map { it.key } + changes.updates.map { it.entry.key }

            changedKeys.toSet().size shouldBe changedKeys.size
            for (insert in changes.inserts) {
                requireThat(insert.key !in local) { "insert of ${insert.key}, which has a local row" }
                insert.favorite shouldBe true
            }
            for (update in changes.updates) {
                val row = local[update.entry.key] ?: throw AssertionError("update of ${update.entry.key} without a row")
                val rows = row.urls()
                val written = update.chapters.map { it.url }
                written.toSet().size shouldBe written.size
                requireThat(rows.containsAll(written)) { "${update.entry.key} adds rows ${written - rows}" }
                requireThat(update.entry.urls().containsAll(rows)) { "${update.entry.key} drops rows" }
            }
            // The new base records exactly the observed rows of every merged entry with a local row.
            for ((key, urls) in result.localPresent) urls shouldBe local.getValue(key).urls()
            for (m in result.merged.entries) {
                if (m.key in local) requireThat(m.key in result.localPresent) { "no localPresent for ${m.key}" }
            }
        }

    @Test
    fun `P7 history clears propagate one-sided, re-reads beat clears, duration is max`() =
        check("P7 history", MergeArbs.history) { case ->
            val result = mergeBucket(
                BaseBucket(Bucket(INDEX, listOf(case.base)), mapOf(case.base.key to case.base.urls())),
                listOf(case.base.after(case.local)),
                Bucket(INDEX, listOf(case.base.after(case.remote))),
            )
            val merged = result.merged.entries.single().chapters

            merged.size shouldBe case.base.chapters.size
            case.base.chapters.forEachIndexed { i, b ->
                val (readAt, durationMs) = expectedHistory(b, case.local[i], case.remote[i])
                val m = merged[i]
                Triple(b.url, m.historyReadAtMillis, m.historyDurationMs) shouldBe Triple(b.url, readAt, durationMs)
                Triple(m.read, m.bookmark, m.lastPageRead) shouldBe Triple(b.read, b.bookmark, b.lastPageRead)
            }
        }

    @Test
    fun `P8 merge output round trips and its bytes do not depend on input order`() =
        check("P8 entries codec", MergeArbs.anyMerge) { s ->
            val result = merge(s)
            val bytes = SyncCodec.encodeBucket(result.merged)

            result.merged.canonical() shouldBe result.merged
            SyncCodec.decodeBucket(bytes) shouldBe result.merged
            val random = Random(s.hashCode())
            val shuffled = merge(
                s.copy(
                    base = s.base?.let {
                        it.copy(bucket = it.bucket.copy(entries = it.bucket.entries.shuffled(random)))
                    },
                    local = s.local.shuffled(random).map { it.copy(chapters = it.chapters.shuffled(random)) },
                    remote = s.remote?.let { it.copy(entries = it.entries.shuffled(random)) },
                ),
            )
            SyncCodec.encodeBucket(shuffled.merged).contentEquals(bytes) shouldBe true
        }

    @Test
    fun `P8 chapter metadata and index-only rows never dirty a bucket`() =
        check("P8 entries digest", MergeArbs.synced) { s ->
            val churned = s.localRows.map { row ->
                row.copy(
                    title = row.title + "!",
                    chapters = row.chapters.map {
                        it.copy(
                            name = it.name + "!",
                            chapterNumber = it.chapterNumber + 1,
                            scanlator = "other",
                            sourceOrder = it.sourceOrder + 7,
                            dateUploadMillis = 1,
                            memoJson = "{\"changed\":true}",
                        )
                    } + (0..<3).map { ChapterRecord(url = "/listed-since/$it", name = "New $it") },
                )
            }
            val result = mergeBucket(s.base, churned, s.base.bucket)

            result.remoteDirty shouldBe false
            result.merged shouldBe s.base.bucket
            result.localChanges.isEmpty shouldBe true
        }

    @Test
    fun `P9 a base chapter with no local row never changes the remote`() =
        check("P9 entries unknown", MergeArbs.anyThreeWay) { s ->
            val base = s.base!!
            val result = merge(s)
            val merged = result.merged.entries.associateBy { it.key }
            val local = s.local.associateBy { it.key }
            val remote = s.remote?.entries.orEmpty().associateBy { it.key }

            for (b in base.bucket.entries) {
                val m = merged[b.key] ?: continue
                val present = base.localPresent[b.key].orEmpty()
                val observed = local[b.key]?.urls().orEmpty()
                val mChapters = m.chapters.associateBy { it.url }
                val rChapters = remote[b.key]?.chapters.orEmpty().associateBy { it.url }
                for (bc in b.chapters) {
                    if (bc.url !in present || bc.url in observed) continue
                    (mChapters[bc.url]?.state() ?: DEFAULT_STATE) shouldBe (rChapters[bc.url] ?: bc).state()
                    requireThat(result.localPresent[b.key]?.contains(bc.url) != true) {
                        "${b.key} ${bc.url} has no local row but is recorded as present"
                    }
                }
            }
        }

    private fun merge(s: ThreeWay) = mergeBucket(s.base, s.local, s.remote, s.options)

    private fun mergeBucket(
        base: BaseBucket?,
        local: List<EntryRecord>,
        remote: Bucket?,
        options: EntryMergeOptions = EntryMergeOptions(nowMillis = NOW),
    ): EntryMergeResult = EntryMerge.mergeBucket(INDEX, base, local, remote, options)
}

/** Chapter state; [SyncedView.chapters] leaves default states out, as the entry digest does. */
private data class StateView(
    val read: Boolean,
    val bookmark: Boolean,
    val lastPageRead: Long,
    val readAt: Long?,
    val durationMs: Long,
)

private val DEFAULT_STATE = ChapterRecord(url = "").state()

/** Every synced entry field except favoriteAt and removedAt, which the properties check on their own. */
private data class SyncedView(
    val favorite: Boolean,
    val viewerFlags: Long,
    val chapterFlags: Long,
    val notes: String,
    val categoryUuids: List<String>,
    val excludedScanlators: List<String>,
    val dedupOverride: DedupOverrideRecord?,
    val tracks: List<TrackRecord>,
    val chapters: Map<String, StateView>,
)

/** The fields outside the syncing-device-wins set (plan "Merge rules"). */
private data class SymmetricView(
    val key: EntryKey,
    val favorite: Boolean,
    val favoriteAtMillis: Long?,
    val removedAtMillis: Long?,
    val categoryUuids: List<String>,
    val excludedScanlators: List<String>,
    val chapters: Map<String, StateView>,
    val trackProgress: Map<Long, Pair<Double, Long>>,
)

private fun ChapterRecord.state() = StateView(read, bookmark, lastPageRead, historyReadAtMillis, historyDurationMs)

private fun EntryRecord.states(): Map<String, StateView> =
    chapters.filterNot { it.hasDefaultState }.associate { it.url to it.state() }

private fun EntryRecord.urls(): Set<String> = chapters.mapTo(HashSet()) { it.url }

private fun EntryRecord.synced() = SyncedView(
    favorite,
    viewerFlags,
    chapterFlags,
    notes,
    categoryUuids,
    excludedScanlators,
    dedupOverride,
    tracks,
    states(),
)

private fun EntryRecord.symmetricView() = SymmetricView(
    key,
    favorite,
    favoriteAtMillis,
    removedAtMillis,
    categoryUuids,
    excludedScanlators,
    states(),
    tracks.associate { it.trackerId to (it.lastChapterRead to it.totalChapters) },
)

private fun RemovalOrigin.flipped() = if (this == RemovalOrigin.LOCAL) RemovalOrigin.REMOTE else RemovalOrigin.LOCAL

private fun requireThat(condition: Boolean, message: () -> String) {
    if (!condition) throw AssertionError(message())
}

/**
 * What an applier following the change set leaves in the local database: listed entry fields, chapter states and
 * tracks written onto existing rows, and inserted entries with their full chapter index.
 */
private fun applyLocal(rows: List<EntryRecord>, changes: LocalChangeSet): Map<EntryKey, EntryRecord> {
    val out = rows.associateByTo(HashMap()) { it.key }
    for (update in changes.updates) {
        val target = update.entry
        var row = out.getValue(target.key)
        for (field in update.fields) {
            row = when (field) {
                EntryField.FAVORITE -> row.copy(favorite = target.favorite)
                EntryField.VIEWER_FLAGS -> row.copy(viewerFlags = target.viewerFlags)
                EntryField.CHAPTER_FLAGS -> row.copy(chapterFlags = target.chapterFlags)
                EntryField.NOTES -> row.copy(notes = target.notes)
                EntryField.CATEGORIES -> row.copy(categoryUuids = target.categoryUuids)
                EntryField.EXCLUDED_SCANLATORS -> row.copy(excludedScanlators = target.excludedScanlators)
                EntryField.DEDUP_OVERRIDE -> row.copy(dedupOverride = target.dedupOverride)
            }
        }
        val written = update.chapters.associateBy { it.url }
        val upserted = update.upsertTracks.mapTo(HashSet()) { it.trackerId }
        val kept = row.tracks.filterNot { it.trackerId in upserted || it.trackerId in update.deleteTrackerIds }
        out[target.key] = row.copy(
            chapters = row.chapters.map { chapter ->
                val state = written[chapter.url] ?: return@map chapter
                chapter.copy(
                    read = state.read,
                    bookmark = state.bookmark,
                    lastPageRead = state.lastPageRead,
                    historyReadAtMillis = state.historyReadAtMillis,
                    historyDurationMs = state.historyDurationMs,
                )
            },
            tracks = (kept + update.upsertTracks).sortedBy { it.trackerId },
        )
    }
    for (insert in changes.inserts) out[insert.key] = insert
    return out
}

private fun EntryRecord.after(actions: List<HistoryAction>) = copy(
    chapters = chapters.mapIndexed { i, chapter ->
        when (val action = actions[i]) {
            HistoryAction.Same -> chapter
            HistoryAction.Clear -> chapter.copy(historyReadAtMillis = null, historyDurationMs = 0)
            is HistoryAction.Reread -> chapter.copy(
                historyReadAtMillis = action.readAt,
                historyDurationMs = action.durationMs,
            )
        }
    },
)

/** Plan P7: a one-sided clear propagates, a re-read beats a concurrent clear, and durations take the max. */
private fun expectedHistory(base: ChapterRecord, local: HistoryAction, remote: HistoryAction): Pair<Long?, Long> {
    val rereads = listOf(local, remote).filterIsInstance<HistoryAction.Reread>()
    return when {
        rereads.isNotEmpty() -> rereads.maxOf { it.readAt } to rereads.maxOf { it.durationMs }
        HistoryAction.Clear in listOf(local, remote) -> null to 0L
        else -> base.historyReadAtMillis to base.historyDurationMs
    }
}

/**
 * Plan P5: no read→unread, bookmark→none, favorite→removed, page decrease, membership loss, history readAt decrease
 * or non-default scalar → default, on the entry, its chapters and its tracks.
 */
private fun EntryRecord.shouldNotRegressFrom(side: EntryRecord) {
    val subject = "${side.key}"
    requireThat(favorite || !side.favorite) { "$subject unfavorited" }
    side.favoriteAtMillis?.let { known ->
        val merged = favoriteAtMillis
        requireThat(merged != null && merged <= known) { "$subject favoriteAt $known -> $merged" }
    }
    requireThat(side.viewerFlags == 0L || viewerFlags != 0L) { "$subject viewerFlags reset" }
    requireThat(side.chapterFlags == 0L || chapterFlags != 0L) { "$subject chapterFlags reset" }
    requireThat(side.notes.isEmpty() || notes.isNotEmpty()) { "$subject notes reset" }
    requireThat(categoryUuids.containsAll(side.categoryUuids)) { "$subject lost categories" }
    requireThat(excludedScanlators.containsAll(side.excludedScanlators)) { "$subject lost excluded scanlators" }
    side.dedupOverride?.let { o ->
        val m = dedupOverride
        requireThat(o.groupChapters == null || m?.groupChapters != null) { "$subject dedup groupChapters reset" }
        requireThat(o.mergeSplitChapters == null || m?.mergeSplitChapters != null) { "$subject dedup mergeSplit reset" }
        requireThat(o.rankedScanlators == null || m?.rankedScanlators != null) { "$subject dedup ranking reset" }
    }
    val chaptersByUrl = chapters.associateBy { it.url }
    for (c in side.chapters) {
        val m = chaptersByUrl[c.url] ?: throw AssertionError("$subject lost chapter ${c.url}")
        val at = "$subject ${c.url}"
        requireThat(m.read || !c.read) { "$at unread" }
        requireThat(m.bookmark || !c.bookmark) { "$at unbookmarked" }
        requireThat(m.lastPageRead >= c.lastPageRead) { "$at page ${c.lastPageRead} -> ${m.lastPageRead}" }
        c.historyReadAtMillis?.let { readAt ->
            val merged = m.historyReadAtMillis
            requireThat(merged != null && merged >= readAt) { "$at readAt $readAt -> $merged" }
        }
        requireThat(m.historyDurationMs >= c.historyDurationMs) { "$at duration decreased" }
    }
    val tracksById = tracks.associateBy { it.trackerId }
    for (t in side.tracks) {
        val m = tracksById[t.trackerId] ?: throw AssertionError("$subject lost track ${t.trackerId}")
        val at = "$subject track ${t.trackerId}"
        requireThat(m.lastChapterRead >= t.lastChapterRead) { "$at lastChapterRead decreased" }
        requireThat(m.totalChapters >= t.totalChapters) { "$at totalChapters decreased" }
        requireThat(t.remoteId == 0L || m.remoteId != 0L) { "$at remoteId reset" }
        requireThat(t.libraryId == null || m.libraryId != null) { "$at libraryId reset" }
        requireThat(t.title.isEmpty() || m.title.isNotEmpty()) { "$at title reset" }
        requireThat(t.status == 0L || m.status != 0L) { "$at status reset" }
        requireThat(t.score == 0.0 || m.score != 0.0) { "$at score reset" }
        requireThat(t.remoteUrl.isEmpty() || m.remoteUrl.isNotEmpty()) { "$at remoteUrl reset" }
        requireThat(t.startDateMillis == null || m.startDateMillis != null) { "$at startDate reset" }
        requireThat(t.finishDateMillis == null || m.finishDateMillis != null) { "$at finishDate reset" }
        requireThat(!t.private || m.private) { "$at private reset" }
    }
}
