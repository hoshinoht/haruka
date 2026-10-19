package haruka.sync.merge

import haruka.sync.model.Bucket
import haruka.sync.model.ChapterRecord
import haruka.sync.model.DedupOverrideRecord
import haruka.sync.model.EntryKey
import haruka.sync.model.EntryRecord
import haruka.sync.model.TrackRecord
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class EntryMergeTest {

    // --- Chapter state ---

    @Test
    fun `remote read lands on the local row without dirtying the remote`() {
        val base = entry(ch("1"))
        val result = merge(base, base, entry(ch("1", read = true)))

        result.remoteDirty shouldBe false
        result.single().chapters.single().read shouldBe true
        result.localChanges.updates.single().chapters shouldContainExactly listOf(ch("1", read = true))
        result.localChanges.updates.single().fields.shouldBeEmpty()
    }

    @Test
    fun `reads on different chapters on both sides are both kept`() {
        val base = entry(ch("1"), ch("2"))
        val result = merge(base, entry(ch("1", read = true), ch("2")), entry(ch("1"), ch("2", read = true)))

        result.remoteDirty shouldBe true
        result.single().chapters.map { it.read } shouldBe listOf(true, true)
        result.localChanges.updates.single().chapters shouldContainExactly listOf(ch("2", read = true))
    }

    @Test
    fun `one-sided unread and unbookmark propagate`() {
        val base = entry(ch("1", read = true, bookmark = true))
        val result = merge(base, base, entry(ch("1")))

        result.single().chapters.single().hasDefaultState shouldBe true
        result.localChanges.updates.single().chapters shouldContainExactly listOf(ch("1"))
    }

    @Test
    fun `lastPageRead changed on both sides takes the max`() {
        val base = entry(ch("1", lastPageRead = 5))
        val result = merge(base, entry(ch("1", lastPageRead = 8)), entry(ch("1", lastPageRead = 12)))

        result.single().chapters.single().lastPageRead shouldBe 12
        result.conflicts.shouldBeEmpty()
    }

    @Test
    fun `history changed on both sides takes the later read and the longer duration`() {
        val base = entry(ch("1", readAt = 1_000, duration = 100))
        val result = merge(
            base,
            entry(ch("1", readAt = 2_000, duration = 150)),
            entry(ch("1", readAt = 3_000, duration = 120)),
        )

        result.single().chapters.single().historyReadAtMillis shouldBe 3_000
        result.single().chapters.single().historyDurationMs shouldBe 150
    }

    @Test
    fun `a re-read beats a concurrent history clear`() {
        val base = entry(ch("1", readAt = 1_000, duration = 100))
        val result = merge(base, entry(ch("1")), entry(ch("1", readAt = 2_000, duration = 130)))

        result.single().chapters.single() shouldBe ch("1", readAt = 2_000, duration = 130)
    }

    @Test
    fun `a one-sided history clear propagates and resets the duration`() {
        val base = entry(ch("1", readAt = 1_000, duration = 100))
        // The remote record carries a stray duration; a cleared history always means duration 0.
        val result = merge(base, base, entry(ch("1", readAt = null, duration = 40)))

        result.single().chapters.single().historyReadAtMillis shouldBe null
        result.single().chapters.single().historyDurationMs shouldBe 0
        result.localChanges.updates.single().chapters shouldContainExactly listOf(ch("1"))
    }

    // --- favoriteAt ---

    @Test
    fun `favoriteAt is a min register`() {
        val result = merge(
            entry().copy(favoriteAtMillis = 200),
            entry().copy(favoriteAtMillis = 150),
            entry().copy(favoriteAtMillis = 300),
        )

        result.single().favoriteAtMillis shouldBe 150
    }

    @Test
    fun `favoriteAt sentinel 0 never wins`() {
        val result = merge(null, entry().copy(favoriteAtMillis = 0), entry().copy(favoriteAtMillis = 500))

        result.single().favoriteAtMillis shouldBe 500
    }

    @Test
    fun `a raised favoriteAt is not a change and never reaches the local row`() {
        val base = entry().copy(favoriteAtMillis = 100)
        val result = merge(base, entry().copy(favoriteAtMillis = 400), base)

        result.remoteDirty shouldBe false
        result.single().favoriteAtMillis shouldBe 100
        result.localChanges.isEmpty shouldBe true
    }

    // --- Sets ---

    @Test
    fun `category membership merges per element`() {
        merge(entry(cats = "a"), entry(cats = "a,x"), entry(cats = "a")).single().categoryUuids shouldBe
            listOf("a", "x")
        merge(entry(cats = "a,y"), entry(cats = "a"), entry(cats = "a,y")).single().categoryUuids shouldBe
            listOf("a")
        merge(entry(cats = "a"), entry(cats = "a,x"), entry(cats = "")).single().categoryUuids shouldBe
            listOf("x")
    }

    @Test
    fun `a remote membership change is applied locally`() {
        val result = merge(entry(cats = "a"), entry(cats = "a"), entry(cats = "b"))

        result.localChanges.updates.single().fields shouldBe setOf(EntryField.CATEGORIES)
        result.localChanges.updates.single().entry.categoryUuids shouldBe listOf("b")
    }

    @Test
    fun `excluded scanlators merge per element`() {
        val base = entry().copy(excludedScanlators = listOf("A", "B"))
        val result = merge(
            base,
            base.copy(excludedScanlators = listOf("A", "B", "C")),
            base.copy(excludedScanlators = listOf("B")),
        )

        result.single().excludedScanlators shouldBe listOf("B", "C")
        result.localChanges.updates.single().fields shouldBe setOf(EntryField.EXCLUDED_SCANLATORS)
    }

    @Test
    fun `memberships follow collapsed categories and drop deleted ones`() {
        val result = merge(
            entry(cats = "x2"),
            entry(cats = "x2"),
            entry(cats = "x2,z"),
            options = EntryMergeOptions(
                nowMillis = NOW,
                categoryRemap = mapOf("x2" to "x1"),
                deletedCategories = setOf("z"),
            ),
        )

        result.single().categoryUuids shouldBe listOf("x1")
        result.localChanges.updates.single().entry.categoryUuids shouldBe listOf("x1")
    }

    // --- Settings: syncing device wins ---

    @Test
    fun `conflicting flags and notes keep the syncing device's value and are counted`() {
        val base = entry().copy(viewerFlags = 0, notes = "")
        val result =
            merge(base, base.copy(viewerFlags = 1, notes = "mine"), base.copy(viewerFlags = 2, notes = "theirs"))

        result.single().viewerFlags shouldBe 1
        result.single().notes shouldBe "mine"
        result.conflicts.map { it.field } shouldBe listOf("viewerFlags", "notes")
        result.localChanges.isEmpty shouldBe true
    }

    @Test
    fun `a one-sided remote flag change is applied locally`() {
        val base = entry().copy(chapterFlags = 0)
        val result = merge(base, base, base.copy(chapterFlags = 4))

        result.localChanges.updates.single().fields shouldBe setOf(EntryField.CHAPTER_FLAGS)
        result.localChanges.updates.single().entry.chapterFlags shouldBe 4
        result.conflicts.shouldBeEmpty()
    }

    @Test
    fun `dedup override merges per field`() {
        val result = merge(
            entry(),
            entry().copy(dedupOverride = DedupOverrideRecord(groupChapters = true)),
            entry().copy(dedupOverride = DedupOverrideRecord(mergeSplitChapters = false)),
        )

        result.single().dedupOverride shouldBe DedupOverrideRecord(groupChapters = true, mergeSplitChapters = false)
        result.localChanges.updates.single().fields shouldBe setOf(EntryField.DEDUP_OVERRIDE)
    }

    @Test
    fun `conflicting dedup override field keeps the syncing device's value`() {
        val base = entry().copy(dedupOverride = DedupOverrideRecord(groupChapters = true))
        val result = merge(
            base,
            entry().copy(dedupOverride = DedupOverrideRecord(groupChapters = false)),
            entry().copy(dedupOverride = null),
        )

        result.single().dedupOverride shouldBe DedupOverrideRecord(groupChapters = false)
        result.conflicts.single().field shouldBe "dedup.groupChapters"
    }

    // --- Tracks ---

    @Test
    fun `track progress takes the max and conflicting status keeps the syncing device's`() {
        val base = entry(tracks = listOf(TrackRecord(1, status = 1, lastChapterRead = 5.0)))
        val result = merge(
            base,
            entry(tracks = listOf(TrackRecord(1, status = 2, lastChapterRead = 7.0))),
            entry(tracks = listOf(TrackRecord(1, status = 3, lastChapterRead = 9.0))),
        )

        result.single().tracks.single() shouldBe TrackRecord(1, status = 2, lastChapterRead = 9.0)
        result.conflicts.single().field shouldBe "track 1 status"
        result.localChanges.updates.single().upsertTracks shouldBe result.single().tracks
    }

    @Test
    fun `a track removed on one side stays removed when the other side left it alone`() {
        val track = TrackRecord(1, status = 1)
        merge(entry(tracks = listOf(track)), entry(), entry(tracks = listOf(track))).single().tracks.shouldBeEmpty()

        val remoteRemoved = merge(entry(tracks = listOf(track)), entry(tracks = listOf(track)), entry())
        remoteRemoved.localChanges.updates.single().deleteTrackerIds shouldBe listOf(1L)
    }

    @Test
    fun `a track removed on one side survives a change on the other`() {
        val changed = TrackRecord(1, status = 1, lastChapterRead = 9.0)
        val result = merge(entry(tracks = listOf(TrackRecord(1, status = 1))), entry(), entry(tracks = listOf(changed)))

        result.single().tracks shouldBe listOf(changed)
        result.localChanges.updates.single().upsertTracks shouldBe listOf(changed)
    }

    @Test
    fun `tracks linked on both sides merge as a union`() {
        val result = merge(
            entry(),
            entry(tracks = listOf(TrackRecord(2, status = 1))),
            entry(tracks = listOf(TrackRecord(2, score = 8.0), TrackRecord(3))),
        )

        result.single().tracks shouldBe listOf(TrackRecord(2, status = 1, score = 8.0), TrackRecord(3))
        result.conflicts.shouldBeEmpty()
    }

    // --- Chapter index ---

    @Test
    fun `index-only additions do not dirty the bucket`() {
        val base = entry(ch("1"))
        val remote = entry(ch("1").copy(name = "Remote name"))
        val result = merge(base, entry(ch("1"), ch("2")), remote)

        result.remoteDirty shouldBe false
        result.merged.entries shouldBe listOf(remote)
        result.localPresent[KEY] shouldBe setOf("1", "2")
    }

    @Test
    fun `chapter index is a union by url that keeps remote metadata`() {
        val base = entry(ch("1"))
        val result = merge(
            base,
            entry(ch("1", read = true), ch("2").copy(name = "Local 2")),
            entry(ch("1").copy(name = "Remote 1")),
        )

        result.remoteDirty shouldBe true
        result.single().chapters shouldBe
            listOf(ch("1", read = true).copy(name = "Remote 1"), ch("2").copy(name = "Local 2"))
    }

    // --- Presence ---

    @Test
    fun `entry missing remotely but in the base is a remote removal`() {
        val result = merge(entry(), entry(), null)

        result.single().favorite shouldBe false
        result.single().removedAtMillis shouldBe NOW
        result.removals shouldBe listOf(EntryRemoval(KEY, RemovalOrigin.REMOTE))
        result.localChanges.updates.single().fields shouldBe setOf(EntryField.FAVORITE)
    }

    @Test
    fun `entry row missing locally is a local removal with every chapter unknown`() {
        val base = entry(ch("1", read = true), ch("2"))
        val result = merge(base, null, entry(ch("1", read = true), ch("2", read = true)))

        result.single().favorite shouldBe false
        result.single().chapters.map { it.read } shouldBe listOf(true, true)
        result.removals shouldBe listOf(EntryRemoval(KEY, RemovalOrigin.LOCAL))
        result.localChanges.isEmpty shouldBe true
        result.localPresent.containsKey(KEY) shouldBe false
    }

    @Test
    fun `a delisted chapter is unknown and keeps the base state`() {
        val base = entry(ch("1", read = true), ch("2"))
        val result = merge(base, entry(ch("2")), base, present = setOf("1", "2"))

        result.remoteDirty shouldBe false
        result.single().chapters.first() shouldBe ch("1", read = true)
        result.localPresent[KEY] shouldBe setOf("2")
        result.localChanges.isEmpty shouldBe true
    }

    @Test
    fun `remote changes to an unknown chapter are kept but not applied locally`() {
        val base = entry(ch("1", read = true), ch("2"))
        val result = merge(base, entry(ch("2")), entry(ch("1", read = true, bookmark = true), ch("2")))

        result.single().chapters.first() shouldBe ch("1", read = true, bookmark = true)
        result.localChanges.isEmpty shouldBe true
    }

    @Test
    fun `a delisted read chapter re-listed unread stays read everywhere`() {
        val start = entry(ch("1", read = true), ch("2"))
        val delisted = merge(start, entry(ch("2")), start, present = setOf("1", "2"))

        // Next sync: the base is the previous result; the source lists chapter 1 again as a fresh, unread row.
        val relisted = merge(
            delisted.single(),
            entry(ch("1"), ch("2")),
            delisted.single(),
            present = delisted.localPresent.getValue(KEY),
        )

        relisted.remoteDirty shouldBe false
        relisted.single().chapters.first().read shouldBe true
        relisted.localChanges.updates.single().chapters shouldContainExactly listOf(ch("1", read = true))
        relisted.localPresent[KEY] shouldBe setOf("1", "2")
    }

    @Test
    fun `a new local row merges remote progress onto it`() {
        val base = entry(ch("1"))
        val progressed = ch("2", lastPageRead = 7, read = true)
        val result = merge(base, entry(ch("1"), ch("2", lastPageRead = 3)), entry(ch("1"), progressed))

        result.single().chapters.last() shouldBe progressed
        result.localChanges.updates.single().chapters shouldContainExactly listOf(progressed)
    }

    @Test
    fun `a favorite with no local row is inserted with its full chapter index`() {
        val remote = entry(ch("1", read = true), ch("2"))
        val result = merge(null, null, remote)

        result.localChanges.inserts shouldBe listOf(remote)
        result.remoteDirty shouldBe false
    }

    @Test
    fun `a local row outside the library unknown to the remote is not synced`() {
        val result = merge(null, entry(ch("1", read = true)).copy(favorite = false), null)

        result.merged.entries.shouldBeEmpty()
        result.remoteDirty shouldBe false
        result.localChanges.isEmpty shouldBe true
    }

    @Test
    fun `merging the base with itself changes nothing`() {
        val base =
            entry(ch("1", read = true, readAt = 5, duration = 9), ch("2"), cats = "a", tracks = listOf(TrackRecord(1)))
        val result = merge(base, base, base)

        result.merged shouldBe Bucket(0, listOf(base))
        result.remoteDirty shouldBe false
        result.localChanges.isEmpty shouldBe true
        result.conflicts.shouldBeEmpty()
        result.removals.shouldBeEmpty()
    }

    // --- Tombstones ---

    @Test
    fun `a local removal becomes a tombstone stamped now`() {
        val result = merge(entry(), entry().copy(favorite = false), entry())

        result.remoteDirty shouldBe true
        result.single().favorite shouldBe false
        result.single().removedAtMillis shouldBe NOW
        result.removals shouldBe listOf(EntryRemoval(KEY, RemovalOrigin.LOCAL))
    }

    @Test
    fun `tombstones are pruned after 180 days`() {
        val old = entry().copy(favorite = false, removedAtMillis = NOW - 181 * DAY)
        val pruned = merge(old, null, old)
        pruned.merged.entries.shouldBeEmpty()
        pruned.remoteDirty shouldBe true

        val young = entry().copy(favorite = false, removedAtMillis = NOW - 179 * DAY)
        val kept = merge(young, null, young)
        kept.merged.entries shouldBe listOf(young)
        kept.remoteDirty shouldBe false
    }

    @Test
    fun `re-favoriting a tombstone clears its removal time`() {
        val tomb = entry().copy(favorite = false, removedAtMillis = NOW - DAY)
        val result = merge(tomb, entry(), tomb)

        result.single().favorite shouldBe true
        result.single().removedAtMillis shouldBe null
    }

    // --- Union mode ---

    @Test
    fun `union mode ORs state, takes max progress and unions sets`() {
        val result = merge(
            base = null,
            local = entry(ch("1", read = true, lastPageRead = 3), cats = "x").copy(notes = "mine"),
            remote = entry(ch("1", bookmark = true, lastPageRead = 5), ch("2", read = true), cats = "y"),
            union = true,
        )

        result.single().chapters shouldBe listOf(
            ch("1", read = true, bookmark = true, lastPageRead = 5),
            ch("2", read = true),
        )
        result.single().categoryUuids shouldBe listOf("x", "y")
        // A non-default scalar beats the default.
        result.single().notes shouldBe "mine"
        result.conflicts.shouldBeEmpty()
    }

    @Test
    fun `union mode keeps the syncing device's value when both scalars are set`() {
        val result = merge(null, entry().copy(viewerFlags = 1), entry().copy(viewerFlags = 2), union = true)

        result.single().viewerFlags shouldBe 1
        result.conflicts.single().field shouldBe "viewerFlags"
    }

    @Test
    fun `union mode never applies entry removals`() {
        val tomb = entry().copy(favorite = false, removedAtMillis = NOW - DAY)
        val result = merge(entry(), entry(), tomb, union = true)

        result.single().favorite shouldBe true
        result.removals.shouldBeEmpty()
        result.localChanges.isEmpty shouldBe true
    }

    // --- Mass-removal guard ---

    @Test
    fun `a held local removal stays favorite remotely and locally unchanged`() {
        val result = merge(
            entry(),
            entry().copy(favorite = false),
            entry(),
            options = EntryMergeOptions(nowMillis = NOW, holdLocalRemovals = true),
        )

        result.single().favorite shouldBe true
        result.remoteDirty shouldBe false
        result.heldRemovals shouldBe listOf(EntryRemoval(KEY, RemovalOrigin.LOCAL))
        result.removals.shouldBeEmpty()
        result.localChanges.isEmpty shouldBe true
    }

    @Test
    fun `a held remote removal is committed but not applied locally`() {
        val tomb = entry().copy(favorite = false, removedAtMillis = NOW - DAY)
        val result =
            merge(entry(), entry(), tomb, options = EntryMergeOptions(nowMillis = NOW, holdRemoteRemovals = true))

        result.merged.entries shouldBe listOf(tomb)
        result.heldRemovals shouldBe listOf(EntryRemoval(KEY, RemovalOrigin.REMOTE))
        result.localChanges.isEmpty shouldBe true
    }

    @Test
    fun `a confirmed removal is never held`() {
        val tomb = entry().copy(favorite = false, removedAtMillis = NOW - DAY)
        val result = merge(
            entry(),
            entry(),
            tomb,
            options = EntryMergeOptions(nowMillis = NOW, holdRemoteRemovals = true, confirmedRemovals = setOf(KEY)),
        )

        result.heldRemovals.shouldBeEmpty()
        result.removals shouldBe listOf(EntryRemoval(KEY, RemovalOrigin.REMOTE))
        result.localChanges.updates.single().fields shouldBe setOf(EntryField.FAVORITE)
    }

    @Test
    fun `mass removal means more than 25 entries or more than 10 percent`() {
        isMassRemoval(removals = 26, librarySize = 1000) shouldBe true
        isMassRemoval(removals = 25, librarySize = 1000) shouldBe false
        isMassRemoval(removals = 11, librarySize = 100) shouldBe true
        isMassRemoval(removals = 10, librarySize = 100) shouldBe false
        isMassRemoval(removals = 0, librarySize = 0) shouldBe false
    }

    @Test
    fun `merge output is canonical`() {
        val result = merge(null, entry(ch("2"), ch("1", read = true)).copy(categoryUuids = listOf("b", "a")), null)

        result.single().chapters.map { it.url } shouldBe listOf("1", "2")
        result.single().categoryUuids shouldBe listOf("a", "b")
    }

    private fun EntryMergeResult.single(): EntryRecord = merged.entries.single()

    private fun merge(
        base: EntryRecord?,
        local: EntryRecord?,
        remote: EntryRecord?,
        present: Set<String> = base?.chapters?.mapTo(HashSet()) { it.url }.orEmpty(),
        union: Boolean = false,
        options: EntryMergeOptions = EntryMergeOptions(nowMillis = NOW),
    ): EntryMergeResult {
        val baseBucket = BaseBucket(
            bucket = Bucket(0, listOfNotNull(base)),
            localPresent = if (base != null) mapOf(KEY to present) else emptyMap(),
        )
        return EntryMerge.mergeBucket(
            index = 0,
            base = if (union) null else baseBucket,
            local = listOfNotNull(local),
            remote = remote?.let { Bucket(0, listOf(it)) },
            options = options,
        )
    }

    private companion object {
        val KEY = EntryKey(sourceId = 1, url = "/m/1")
        const val NOW = 1_800_000_000_000L
        const val DAY = 24L * 60 * 60 * 1000

        fun entry(
            vararg chapters: ChapterRecord,
            cats: String = "",
            tracks: List<TrackRecord> = emptyList(),
        ) = EntryRecord(
            key = KEY,
            favorite = true,
            favoriteAtMillis = 100,
            title = "Title",
            categoryUuids = cats.split(',').filter(String::isNotEmpty),
            chapters = chapters.toList(),
            tracks = tracks,
        )

        fun ch(
            url: String,
            read: Boolean = false,
            bookmark: Boolean = false,
            lastPageRead: Long = 0,
            readAt: Long? = null,
            duration: Long = 0,
        ) = ChapterRecord(
            url = url,
            name = "Chapter $url",
            read = read,
            bookmark = bookmark,
            lastPageRead = lastPageRead,
            historyReadAtMillis = readAt,
            historyDurationMs = duration,
        )
    }
}
