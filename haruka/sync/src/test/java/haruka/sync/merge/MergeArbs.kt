package haruka.sync.merge

import haruka.sync.model.Bucket
import haruka.sync.model.CategoriesPayload
import haruka.sync.model.CategoryRecord
import haruka.sync.model.ChapterRecord
import haruka.sync.model.DedupOverrideRecord
import haruka.sync.model.EntryKey
import haruka.sync.model.EntryRecord
import haruka.sync.model.PrefRecord
import haruka.sync.model.PrefsPayload
import haruka.sync.model.ScanlatorRanking
import haruka.sync.model.TrackRecord
import haruka.sync.model.canonical
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import kotlin.random.Random

/*
 * Constructive generators for the merge properties (plan section "Test properties"). Every scenario starts from a
 * base as a previous sync leaves it (canonical; favorites without removedAt; tombstones younger than 180 days
 * unless a generator says otherwise; favoriteAt never Mihon's 0 sentinel) and derives each side by edits a device
 * can actually make, so a property never fails on input no device can produce. Keys, chapter urls, categories,
 * scanlators and trackers come from small pools so both sides touch the same items. The mass-removal guard is off
 * unless a generator randomizes the options.
 */
internal object MergeArbs {

    const val ITERATIONS = 1_000
    const val INDEX = 0
    const val NOW = 1_800_000_000_000L
    const val DAY = 24L * 60 * 60 * 1000

    /** Runs [property] on [ITERATIONS] samples with a fresh seed, printed so a failure can be replayed. */
    fun <T> check(name: String, arb: Arb<T>, property: suspend (T) -> Unit) {
        val seed = Random.nextLong()
        println("$name seed=$seed iterations=$ITERATIONS")
        runBlocking { checkAll(ITERATIONS, PropTestConfig(seed = seed), arb) { property(it) } }
    }

    // --- Entries ---

    /** A base bucket and this device's rows as the last sync left them (`local = b`). */
    val synced: Arb<Synced> = arbitrary { EntryGen(it.random).synced() }

    /** Local edits only; the remote is the base bucket. */
    val localOnly: Arb<ThreeWay> = arbitrary { rs ->
        val gen = EntryGen(rs.random)
        val synced = gen.synced()
        ThreeWay(synced.base, gen.editLocal(synced), synced.base.bucket)
    }

    /** Remote edits only; the local rows are the base's. */
    val remoteOnly: Arb<RemoteOnly> = arbitrary { rs ->
        val gen = EntryGen(rs.random)
        val synced = gen.synced()
        RemoteOnly(synced, gen.editRemote(synced.base.bucket, prune = false))
    }

    /** Independent edits on both sides of one base; nothing is pruned and the guard is off. */
    val twoSided: Arb<ThreeWay> = arbitrary { EntryGen(it.random).threeWay(prune = false, randomOptions = false) }

    /** Like [twoSided], plus pruned remote records, expired tombstones and random guard and category options. */
    val anyThreeWay: Arb<ThreeWay> = arbitrary { EntryGen(it.random).threeWay(prune = true, randomOptions = true) }

    /** [anyThreeWay] or, one time in five, a union-mode merge. */
    val anyMerge: Arb<ThreeWay> = arbitrary { rs ->
        val gen = EntryGen(rs.random)
        if (rs.random.nextInt(5) == 0) gen.union() else gen.threeWay(prune = true, randomOptions = true)
    }

    /** Two full views of one base (every chapter observed), edited the same way, so they can swap roles. */
    val symmetric: Arb<Symmetric> = arbitrary { rs ->
        val gen = EntryGen(rs.random)
        val synced = gen.synced(fullPresence = true)
        Symmetric(synced.base, gen.editLocal(synced, noise = false), gen.editLocal(synced, noise = false))
    }

    /** No usable base: local rows and a remote bucket drawn independently over shared pools. */
    val union: Arb<ThreeWay> = arbitrary { EntryGen(it.random).union() }

    val history: Arb<HistoryCase> = arbitrary { EntryGen(it.random).history() }

    data class Synced(val base: BaseBucket, val localRows: List<EntryRecord>)

    data class ThreeWay(
        val base: BaseBucket?,
        val local: List<EntryRecord>,
        val remote: Bucket?,
        val options: EntryMergeOptions = EntryMergeOptions(nowMillis = NOW),
    )

    data class RemoteOnly(val synced: Synced, val remote: Bucket)

    data class Symmetric(val base: BaseBucket, val a: List<EntryRecord>, val b: List<EntryRecord>)

    sealed interface HistoryAction {
        data object Same : HistoryAction
        data object Clear : HistoryAction
        data class Reread(val readAt: Long, val durationMs: Long) : HistoryAction
    }

    /** One entry whose chapters all have history in the base; each side keeps, clears or re-reads each chapter. */
    data class HistoryCase(
        val base: EntryRecord,
        val local: List<HistoryAction>,
        val remote: List<HistoryAction>,
    )

    // --- Categories ---

    val categorySynced: Arb<CategoriesPayload> = arbitrary { CategoryGen(it.random).base() }

    val categoryLocalOnly: Arb<CategoryCase> = arbitrary { rs ->
        val gen = CategoryGen(rs.random)
        val base = gen.base()
        CategoryCase(base, gen.edit(live(base), "l", sharedNames = false), base)
    }

    val categoryRemoteOnly: Arb<CategoryCase> = arbitrary { rs ->
        val gen = CategoryGen(rs.random)
        val base = gen.base()
        CategoryCase(base, live(base), gen.remote(base, gen.edit(live(base), "r", sharedNames = false)))
    }

    /** Both sides edited; new and renamed categories may take the same name on both sides. */
    val categoryTwoSided: Arb<CategoryCase> = arbitrary { rs ->
        val gen = CategoryGen(rs.random)
        val base = gen.base()
        val local = gen.edit(live(base), "l", sharedNames = true)
        CategoryCase(base, local, gen.remote(base, gen.edit(live(base), "r", sharedNames = true)))
    }

    /** Two live views of one base with names that never collide, as payloads with or without tombstones. */
    val categorySymmetric: Arb<CategorySymmetric> = arbitrary { rs ->
        val gen = CategoryGen(rs.random)
        val base = gen.base()
        val a = gen.edit(live(base), "a", sharedNames = false)
        val b = gen.edit(live(base), "b", sharedNames = false)
        CategorySymmetric(base, a, gen.remote(base, a, pruneTombstones = true), b, gen.remote(base, b, true))
    }

    val categoryUnion: Arb<CategoryCase> = arbitrary { rs ->
        val gen = CategoryGen(rs.random)
        CategoryCase(null, live(gen.unionSide("l")), gen.unionSide("r"))
    }

    data class CategoryCase(
        val base: CategoriesPayload?,
        val local: List<CategoryRecord>,
        val remote: CategoriesPayload,
    )

    data class CategorySymmetric(
        val base: CategoriesPayload,
        val a: List<CategoryRecord>,
        val aPayload: CategoriesPayload,
        val b: List<CategoryRecord>,
        val bPayload: CategoriesPayload,
    )

    fun live(payload: CategoriesPayload): List<CategoryRecord> = payload.categories.filter {
        it.deletedAtMillis == null
    }

    // --- Prefs ---

    /** Keys a newer build might sync; this build must pass them through untouched. */
    val UNKNOWN_PREF_KEYS = listOf("haruka_future_a", "haruka_future_b")

    /** Local keys outside the whitelist; the merge must ignore them. */
    val IGNORED_PREF_KEYS = listOf("haruka_beta_updates", "haruka_sync_device_id", "haruka_dedup_manga_overrides")

    /** `local = b`: the base and this device's view of it. */
    val prefSynced: Arb<PrefCase> = arbitrary { rs ->
        val gen = PrefGen(rs.random)
        val base = gen.base()
        PrefCase(base, gen.localView(base), base)
    }

    val prefLocalOnly: Arb<PrefCase> = arbitrary { rs ->
        val gen = PrefGen(rs.random)
        val base = gen.base()
        PrefCase(base, gen.localView(gen.edit(base, SYNCED_PREF_KEYS)), base)
    }

    val prefRemoteOnly: Arb<PrefCase> = arbitrary { rs ->
        val gen = PrefGen(rs.random)
        val base = gen.base()
        PrefCase(base, gen.localView(base), gen.edit(base, SYNCED_PREF_KEYS + UNKNOWN_PREF_KEYS))
    }

    val prefTwoSided: Arb<PrefCase> = arbitrary { rs ->
        val gen = PrefGen(rs.random)
        val base = gen.base()
        val local = gen.localView(gen.edit(base, SYNCED_PREF_KEYS))
        PrefCase(base, local, gen.edit(base, SYNCED_PREF_KEYS + UNKNOWN_PREF_KEYS))
    }

    val prefUnion: Arb<PrefCase> = arbitrary { rs ->
        val gen = PrefGen(rs.random)
        PrefCase(null, gen.localView(gen.base()), gen.base())
    }

    data class PrefCase(val base: PrefsPayload?, val local: PrefsPayload, val remote: PrefsPayload)
}

private val CATEGORY_POOL = listOf("c0", "c1", "c2", "c3", "c4", "c5")
private val SCANLATOR_POOL = listOf("s0", "s1", "s2", "s3")
private const val BASE_KEYS = 30
private const val NEW_KEYS = 40
private const val NON_LIBRARY_KEYS = 45
private const val BASE_URLS = 80
private const val ALL_URLS = 100

private enum class Side { LOCAL, REMOTE }

private class EntryGen(private val random: Random) {

    private fun chance(percent: Int) = random.nextInt(100) < percent

    private fun key(n: Int) = EntryKey(sourceId = 1L + n % 3, url = "/m/$n")

    /**
     * Base entries take keys 0..29 and chapter urls 0..79; new entries and new chapters on either side use the rest
     * of the pools, so both sides can add the same item.
     */
    fun synced(fullPresence: Boolean = false, tombstoneMaxAgeDays: Long = 170): MergeArbs.Synced {
        val entries = ArrayList<EntryRecord>()
        val present = HashMap<EntryKey, Set<String>>()
        val rows = ArrayList<EntryRecord>()
        for (n in (0..<BASE_KEYS).shuffled(random).take(random.nextInt(0, 21))) {
            val favorite = chance(80)
            // Base tombstones are at least 5 days old; removals made since then are stamped within the last 5 days.
            val entry = entry(key(n), favorite, 0..<BASE_URLS).copy(
                removedAtMillis = if (favorite) {
                    null
                } else {
                    MergeArbs.NOW -
                        random.nextLong(5, tombstoneMaxAgeDays) * MergeArbs.DAY
                },
            )
            entries += entry
            // Favorites always have a row; an entry removed here may have kept its row or never had one.
            if (!fullPresence && !favorite && chance(50)) continue
            // Some chapters are index-only here: the remote listed them after this device inserted the entry.
            val urls = entry.chapters.mapNotNullTo(HashSet()) { it.url.takeIf { fullPresence || chance(90) } }
            present[entry.key] = urls
            // The local database has no removal time; a full view keeps it so both sides stay interchangeable.
            rows += entry.copy(
                removedAtMillis = entry.removedAtMillis.takeIf { fullPresence },
                chapters = entry.chapters.filter { it.url in urls },
            )
        }
        return MergeArbs.Synced(BaseBucket(Bucket(MergeArbs.INDEX, entries).canonical(), present), rows)
    }

    fun threeWay(prune: Boolean, randomOptions: Boolean): MergeArbs.ThreeWay {
        val synced = synced(tombstoneMaxAgeDays = if (prune) 200 else 170)
        val keys = synced.base.bucket.entries.map { it.key }
        val options = if (randomOptions) {
            EntryMergeOptions(
                nowMillis = MergeArbs.NOW,
                categoryRemap = if (chance(20)) mapOf("c5" to "c0") else emptyMap(),
                deletedCategories = if (chance(20)) CATEGORY_POOL.filterTo(HashSet()) { chance(30) } else emptySet(),
                holdLocalRemovals = chance(30),
                holdRemoteRemovals = chance(30),
                confirmedRemovals = keys.filterTo(HashSet()) { chance(20) },
            )
        } else {
            EntryMergeOptions(nowMillis = MergeArbs.NOW)
        }
        val remote = editRemote(synced.base.bucket, prune).takeUnless { prune && chance(5) }
        return MergeArbs.ThreeWay(synced.base, editLocal(synced), remote, options)
    }

    fun union(): MergeArbs.ThreeWay {
        val local = (0..<BASE_KEYS).shuffled(random).take(random.nextInt(0, 21)).map { n ->
            entry(key(n), favorite = chance(70), 0..<ALL_URLS)
        }
        val remote = (0..<BASE_KEYS).shuffled(random).take(random.nextInt(0, 21)).map { n ->
            val favorite = chance(80)
            entry(key(n), favorite, 0..<ALL_URLS).copy(
                removedAtMillis = if (favorite) null else MergeArbs.NOW - random.nextLong(0, 170 * MergeArbs.DAY),
            )
        }
        val remoteBucket = Bucket(MergeArbs.INDEX, remote).canonical().takeUnless { chance(10) }
        return MergeArbs.ThreeWay(null, local, remoteBucket)
    }

    fun history(): MergeArbs.HistoryCase {
        val chapters = (0..<random.nextInt(1, 11)).map { n ->
            randomState(chapter(n)).copy(
                historyReadAtMillis = random.nextLong(1_000_000, 2_000_000),
                historyDurationMs = random.nextLong(0, 100_000),
            )
        }
        val base = EntryRecord(key = key(0), favorite = true, title = "History", chapters = chapters).canonical()
        // Ties on readAt between the two sides are common enough to matter, so both sides share one draw sometimes.
        val shared = base.chapters.map { random.nextLong(1, 100_000) }
        fun actions() = base.chapters.mapIndexed { i, c ->
            when (random.nextInt(3)) {
                0 -> MergeArbs.HistoryAction.Same
                1 -> MergeArbs.HistoryAction.Clear
                else -> MergeArbs.HistoryAction.Reread(
                    readAt = c.historyReadAtMillis!! + if (chance(20)) shared[i] else random.nextLong(1, 100_000),
                    durationMs = c.historyDurationMs + addedDuration(),
                )
            }
        }
        return MergeArbs.HistoryCase(base, actions(), actions())
    }

    /** This device's rows after local edits, delisted chapters, removed rows and new entries. */
    fun editLocal(synced: MergeArbs.Synced, noise: Boolean = true): List<EntryRecord> {
        val baseKeys = synced.base.bucket.entries.mapTo(HashSet()) { it.key }
        val out = ArrayList<EntryRecord>()
        for (row in synced.localRows) {
            // The row is gone: Mihon deleted the entry (e.g. removed with "delete non-library entries").
            if (chance(3)) continue
            val edited = if (chance(30)) editEntry(row, Side.LOCAL) else row
            out += edited.copy(chapters = editChapters(edited.chapters, Side.LOCAL)).canonical()
        }
        addEntries(out, baseKeys, Side.LOCAL)
        if (noise && chance(30)) {
            // Rows outside the library that no other device knows: there is nothing to sync for them.
            for (n in (NEW_KEYS..<NON_LIBRARY_KEYS).shuffled(random).take(random.nextInt(1, 3))) {
                out += entry(key(n), favorite = false, 0..<ALL_URLS)
            }
        }
        return out
    }

    /** The remote bucket after other devices' commits; only [prune] drops records (expired tombstones). */
    fun editRemote(bucket: Bucket, prune: Boolean): Bucket {
        val out = ArrayList<EntryRecord>()
        for (record in bucket.entries) {
            if (prune && chance(3)) continue
            var edited = if (chance(30)) editEntry(record, Side.REMOTE) else record
            if (chance(5)) edited = edited.copy(title = edited.title + " v2")
            out += edited.copy(chapters = editChapters(edited.chapters, Side.REMOTE)).canonical()
        }
        addEntries(out, bucket.entries.mapTo(HashSet()) { it.key }, Side.REMOTE)
        return Bucket(MergeArbs.INDEX, out).canonical()
    }

    private fun addEntries(out: MutableList<EntryRecord>, taken: Set<EntryKey>, side: Side) {
        if (!chance(30)) return
        for (n in (BASE_KEYS..<NEW_KEYS).shuffled(random).take(random.nextInt(1, 4))) {
            val key = key(n)
            if (key in taken) continue
            // Another device may have added and removed it already; this device only ever adds favorites.
            val favorite = side == Side.LOCAL || chance(80)
            out += entry(key, favorite, 0..<ALL_URLS).copy(
                removedAtMillis = if (favorite) null else MergeArbs.NOW - random.nextLong(0, 5 * MergeArbs.DAY),
            )
        }
    }

    private fun entry(key: EntryKey, favorite: Boolean, urls: IntRange): EntryRecord = EntryRecord(
        key = key,
        favorite = favorite,
        favoriteAtMillis = if (chance(80)) random.nextLong(1, 1_000_000) else null,
        title = "Title ${key.url}",
        author = "Author",
        viewerFlags = if (chance(30)) random.nextLong(1, 16) else 0,
        chapterFlags = if (chance(30)) random.nextLong(1, 16) else 0,
        notes = if (chance(20)) "note ${random.nextInt(4)}" else "",
        categoryUuids = CATEGORY_POOL.filter { chance(25) },
        excludedScanlators = SCANLATOR_POOL.filter { chance(15) },
        dedupOverride = if (chance(20)) dedupOverride() else null,
        chapters = urls.shuffled(random).take(random.nextInt(0, 51)).map { randomState(chapter(it)) },
        tracks = (1L..4L).filter { chance(25) }.map(::track),
    ).canonical()

    private fun chapter(n: Int) = ChapterRecord(
        url = "/c/$n",
        name = "Chapter $n",
        chapterNumber = n.toDouble(),
        scanlator = SCANLATOR_POOL[n % SCANLATOR_POOL.size].takeIf { n % 3 == 0 },
        sourceOrder = n.toLong(),
        dateUploadMillis = 1_000L * n,
    )

    private fun randomState(chapter: ChapterRecord): ChapterRecord {
        if (chance(30)) return chapter
        val readAt = if (chance(30)) random.nextLong(1_000_000, 2_000_000) else null
        return chapter.copy(
            read = chance(50),
            bookmark = chance(10),
            lastPageRead = if (chance(30)) random.nextLong(1, 300) else 0,
            historyReadAtMillis = readAt,
            historyDurationMs = if (readAt != null) random.nextLong(0, 100_000) else 0,
        )
    }

    /** Mihon adds the session time on every history write, and a write without a running timer adds 0. */
    private fun addedDuration() = if (chance(25)) 0 else random.nextLong(1, 50_000)

    private fun dedupOverride() = DedupOverrideRecord(
        groupChapters = if (chance(50)) random.nextBoolean() else null,
        mergeSplitChapters = if (chance(50)) random.nextBoolean() else null,
        rankedScanlators = if (chance(
                50,
            )
        ) {
            ScanlatorRanking(SCANLATOR_POOL.shuffled(random).take(random.nextInt(0, 3)))
        } else {
            null
        },
    )

    private fun track(id: Long) = TrackRecord(
        trackerId = id,
        remoteId = random.nextLong(1, 1_000),
        libraryId = if (chance(50)) random.nextLong(1, 1_000) else null,
        title = "Track $id",
        lastChapterRead = random.nextInt(0, 100).toDouble(),
        totalChapters = random.nextLong(0, 200),
        status = random.nextLong(0, 6),
        score = random.nextInt(0, 11).toDouble(),
        remoteUrl = "https://tracker/$id",
        startDateMillis = if (chance(50)) random.nextLong(1, 1_000_000) else null,
        finishDateMillis = if (chance(30)) random.nextLong(1, 1_000_000) else null,
        private = chance(10),
    )

    private fun editEntry(entry: EntryRecord, side: Side): EntryRecord {
        var out = entry
        repeat(random.nextInt(1, 4)) {
            out = when (random.nextInt(9)) {
                0 -> toggleFavorite(out, side)
                1 -> out.copy(viewerFlags = random.nextLong(0, 16))
                2 -> out.copy(chapterFlags = random.nextLong(0, 16))
                3 -> out.copy(notes = "note ${side.name.lowercase()} ${random.nextInt(4)}")
                4 -> out.copy(categoryUuids = out.categoryUuids.toggle(CATEGORY_POOL.random(random)))
                5 -> out.copy(excludedScanlators = out.excludedScanlators.toggle(SCANLATOR_POOL.random(random)))
                6 -> out.copy(dedupOverride = if (chance(25)) null else dedupOverride())
                7 -> out.copy(tracks = editTracks(out.tracks))
                else -> out.copy(favoriteAtMillis = lowered(out.favoriteAtMillis))
            }
        }
        return out.canonical()
    }

    /**
     * A remote removal carries its tombstone time; a local one has none (the database doesn't record it). Re-adding
     * stamps a new, later favoriteAt locally, while a remote record went through the min-register and never rises.
     */
    private fun toggleFavorite(entry: EntryRecord, side: Side): EntryRecord = if (entry.favorite) {
        entry.copy(
            favorite = false,
            removedAtMillis = if (side == Side.REMOTE) MergeArbs.NOW - random.nextLong(0, 5 * MergeArbs.DAY) else null,
        )
    } else {
        entry.copy(
            favorite = true,
            removedAtMillis = null,
            favoriteAtMillis = if (side == Side.LOCAL) {
                random.nextLong(1_000_000, 2_000_000)
            } else {
                lowered(entry.favoriteAtMillis)
            },
        )
    }

    private fun lowered(favoriteAt: Long?): Long =
        if (favoriteAt == null) random.nextLong(1, 1_000_000) else favoriteAt - random.nextLong(0, favoriteAt)

    private fun editTracks(tracks: List<TrackRecord>): List<TrackRecord> {
        val missing = (1L..4L).filter { id -> tracks.none { it.trackerId == id } }
        return when {
            tracks.isEmpty() || (missing.isNotEmpty() && chance(30)) -> tracks + track(missing.random(random))
            chance(30) -> tracks - tracks.random(random)
            else -> {
                val i = random.nextInt(tracks.size)
                tracks.toMutableList().apply { set(i, editTrack(get(i))) }
            }
        }
    }

    private fun editTrack(track: TrackRecord): TrackRecord = when (random.nextInt(7)) {
        0 -> track.copy(lastChapterRead = track.lastChapterRead + random.nextInt(1, 10))
        1 -> track.copy(totalChapters = track.totalChapters + random.nextLong(1, 10))
        2 -> track.copy(status = random.nextLong(0, 6))
        3 -> track.copy(score = random.nextInt(0, 11).toDouble())
        4 -> track.copy(private = !track.private)
        5 -> track.copy(startDateMillis = random.nextLong(1, 1_000_000))
        else -> track.copy(remoteUrl = "https://tracker/${track.trackerId}/${random.nextInt(4)}")
    }

    /**
     * Chapter rows after one side's activity: state edits, chapters added by a library update and, locally, rows
     * deleted because the source delisted them (UNKNOWN to the merge). The remote may also rename chapters.
     */
    private fun editChapters(chapters: List<ChapterRecord>, side: Side): List<ChapterRecord> {
        val out = ArrayList<ChapterRecord>(chapters.size + 5)
        for (chapter in chapters) {
            if (side == Side.LOCAL && chance(5)) continue
            var edited = if (chance(15)) editState(chapter) else chapter
            if (side == Side.REMOTE &&
                chance(5)
            ) {
                edited = edited.copy(name = edited.name + " v2", sourceOrder = edited.sourceOrder + 1)
            }
            out += edited
        }
        if (chance(30)) {
            val urls = chapters.mapTo(HashSet()) { it.url }
            for (n in (BASE_URLS..<ALL_URLS).shuffled(random).take(random.nextInt(1, 6))) {
                if (chapter(n).url in urls) continue
                out += if (chance(50)) chapter(n) else randomState(chapter(n))
            }
        }
        return out
    }

    private fun editState(chapter: ChapterRecord): ChapterRecord = when (random.nextInt(6)) {
        0 -> chapter.copy(read = !chapter.read)
        1 -> chapter.copy(bookmark = !chapter.bookmark)
        2 -> chapter.copy(lastPageRead = random.nextLong(0, 300))
        3 -> chapter.copy(historyReadAtMillis = null, historyDurationMs = 0)
        4 -> chapter.copy(read = true, lastPageRead = random.nextLong(0, 300))
        else -> chapter.copy(
            historyReadAtMillis = (chapter.historyReadAtMillis ?: 1_000_000) + random.nextLong(1, 100_000),
            historyDurationMs = chapter.historyDurationMs + addedDuration(),
        )
    }

    private fun List<String>.toggle(element: String): List<String> = if (element in
        this
    ) {
        this - element
    } else {
        this + element
    }
}

private class CategoryGen(private val random: Random) {

    private var fresh = 0

    private fun chance(percent: Int) = random.nextInt(100) < percent

    /** Live categories with dense orders and unique names, plus young tombstones of other uuids. */
    fun base(): CategoriesPayload {
        val uuids = (0..<10).shuffled(random).take(random.nextInt(0, 9)).map { "u$it" }
        val liveCount = random.nextInt(0, uuids.size + 1)
        val orders = (0..<liveCount).shuffled(random)
        val live = uuids.take(liveCount).mapIndexed { i, uuid ->
            CategoryRecord(uuid, name = "B$i", order = orders[i].toLong(), flags = random.nextLong(0, 8))
        }
        val tombstones = uuids.drop(liveCount).mapIndexed { i, uuid ->
            CategoryRecord(
                uuid,
                name = "Old$i",
                order = i.toLong(),
                flags = random.nextLong(0, 8),
                deletedAtMillis = MergeArbs.NOW - random.nextLong(5, 170) * MergeArbs.DAY,
            )
        }
        return CategoriesPayload(live + tombstones).canonical()
    }

    /**
     * One device's live categories after deletes, renames, flag changes, reorders and additions. Names stay unique
     * on the device, as Mihon requires; with [sharedNames] a new or renamed category may take a name the other side
     * also picks, which the merge then collapses.
     */
    fun edit(live: List<CategoryRecord>, side: String, sharedNames: Boolean): List<CategoryRecord> {
        val out = ArrayList<CategoryRecord>()
        val names = live.mapTo(HashSet()) { it.name }
        fun name(): String {
            val shared = SHARED_NAMES.random(random)
            return if (sharedNames && chance(40) && shared !in names) shared else "$side-${fresh++}"
        }
        for (category in live.sortedBy { it.order }) {
            if (chance(15)) continue
            var edited = category
            if (chance(20)) edited = edited.copy(name = name().also { names += it })
            if (chance(20)) edited = edited.copy(flags = random.nextLong(0, 8))
            out += edited
        }
        if (chance(30)) {
            repeat(random.nextInt(1, 3)) {
                out +=
                    CategoryRecord("$side${fresh++}", name = name().also { names += it }, flags = random.nextLong(0, 8))
            }
        }
        if (chance(30)) out.shuffle(random)
        return out.mapIndexed { i, category -> category.copy(order = i.toLong()) }
    }

    /** The payload a device commits for [live]: its deletions become tombstones, older tombstones stay. */
    fun remote(
        base: CategoriesPayload,
        live: List<CategoryRecord>,
        pruneTombstones: Boolean = false,
    ): CategoriesPayload {
        val liveUuids = live.mapTo(HashSet()) { it.uuid }
        val deleted = MergeArbs.live(base).filter { it.uuid !in liveUuids && !(pruneTombstones && chance(50)) }.map {
            it.copy(deletedAtMillis = MergeArbs.NOW - random.nextLong(0, 5 * MergeArbs.DAY))
        }
        val tombstones = base.categories.filter { it.deletedAtMillis != null }
        return CategoriesPayload(live + deleted + tombstones).canonical()
    }

    /** A payload drawn from the shared uuid and name pools, unrelated to any base. */
    fun unionSide(side: String): CategoriesPayload {
        val names = HashSet<String>()
        val records = (0..<10).shuffled(random).take(random.nextInt(0, 7)).mapIndexed { i, n ->
            val shared = SHARED_NAMES.random(random)
            val name = if (chance(40) && shared !in names) shared else "$side-$i"
            names += name
            CategoryRecord(
                "u$n",
                name = name,
                order = i.toLong(),
                flags = random.nextLong(0, 8),
                deletedAtMillis = if (chance(20)) MergeArbs.NOW - random.nextLong(0, 170 * MergeArbs.DAY) else null,
            )
        }
        return CategoriesPayload(records).canonical()
    }

    private companion object {
        val SHARED_NAMES = listOf("Reading", "Done", "Later")
    }
}

private class PrefGen(private val random: Random) {

    private fun chance(percent: Int) = random.nextInt(100) < percent

    private fun value() = "v${random.nextInt(4)}"

    fun base(): PrefsPayload = PrefsPayload(
        SYNCED_PREF_KEYS.filter { chance(50) }.map { PrefRecord(it, value()) } +
            MergeArbs.UNKNOWN_PREF_KEYS.filter { chance(30) }.map { PrefRecord(it, value()) },
    ).canonical()

    fun edit(payload: PrefsPayload, keys: Collection<String>): PrefsPayload {
        val values = payload.prefs.associateTo(HashMap()) { it.key to it.value }
        for (key in keys) {
            if (!chance(25)) continue
            if (key in values && chance(30)) values -= key else values[key] = value()
        }
        return PrefsPayload(values.map { (key, value) -> PrefRecord(key, value) }).canonical()
    }

    fun localView(payload: PrefsPayload): PrefsPayload = PrefsPayload(
        payload.prefs.filter { it.key in SYNCED_PREF_KEYS } +
            MergeArbs.IGNORED_PREF_KEYS.filter { chance(50) }.map { PrefRecord(it, value()) },
    ).canonical()
}
