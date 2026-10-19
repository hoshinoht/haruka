package haruka.sync.engine

import haruka.sync.SyncEngine
import haruka.sync.crypto.SyncKey
import haruka.sync.merge.CategoryChangeSet
import haruka.sync.merge.EntryField
import haruka.sync.merge.LocalChangeSet
import haruka.sync.merge.PrefChangeSet
import haruka.sync.model.ChapterRecord
import haruka.sync.model.EntryKey
import haruka.sync.model.EntryRecord
import haruka.sync.model.PrefRecord
import haruka.sync.model.PrefsPayload
import haruka.sync.model.bucketOf
import haruka.sync.remote.FakeRemoteStore
import haruka.sync.remote.RemoteLayout
import haruka.sync.store.BaseStore
import java.nio.file.Files
import java.nio.file.Path
import java.util.TreeMap

/** Thrown from injected crash points; the device is then [SimDevice.restart]ed like a process that died. */
class Crash : RuntimeException("Injected crash")

val LAYOUT = RemoteLayout("app.haruka.test")

/**
 * An in-memory Mihon library behind the engine's [LocalSource] and [LocalApplier], with the upstream behaviour
 * that matters: category ids are local, deleting a category drops its memberships, existing entries never gain
 * chapter rows. Entries store local category ids (as strings) in `categoryUuids`.
 */
class FakeLibrary : LocalSource, LocalApplier {
    data class Category(val name: String, val order: Long, val flags: Long = 0)

    val categories = TreeMap<Long, Category>()
    val entries = TreeMap<EntryKey, EntryRecord>()
    val prefs = TreeMap<String, String>()
    private var nextCategoryId = 1L

    /** Entries the optimistic guard "skips" (reported, not written). */
    var skip: Set<EntryKey> = emptySet()

    /** Entries whose chapter states silently fail to write, reported as unrealized. */
    var unrealize: Set<EntryKey> = emptySet()

    /** Throws [Crash] halfway through that applyEntries call (by running count), after writing part of it. */
    private var crashOnApplyCall: Int? = null
    private var applyCalls = 0

    /** Crashes halfway through the [n]-th applyEntries call from now (1-based); null clears it. */
    fun crashOnApply(n: Int?) {
        crashOnApplyCall = n?.let { applyCalls + it }
    }

    // --- LocalSource ---

    override suspend fun readCategories() = categories.map { (id, c) -> LocalCategoryRow(id, c.name, c.order, c.flags) }

    override suspend fun readPrefs() = PrefsPayload(prefs.map { PrefRecord(it.key, it.value) })

    override suspend fun readBucket(index: Int, knownKeys: Set<EntryKey>, categoryUuids: Map<Long, String>) =
        entries.values
            .filter { bucketOf(it.key) == index && (it.favorite || it.key in knownKeys) }
            .map { read(it, categoryUuids) }

    private fun read(row: EntryRecord, uuids: Map<Long, String>) =
        row.copy(categoryUuids = row.categoryUuids.mapNotNull { uuids[it.toLong()] }.sorted())

    // --- LocalApplier ---

    override suspend fun applyCategories(changes: CategoryChangeSet, localIds: Map<String, Long>): CategoryApplyResult {
        val ids = localIds.toMutableMap()
        for (uuid in changes.delete) {
            val id = ids.remove(uuid) ?: continue
            deleteCategory(id)
        }
        for (c in changes.create) {
            val id = nextCategoryId++
            categories[id] = Category(c.name, c.order, c.flags)
            ids[c.uuid] = id
        }
        for (c in changes.rename) {
            categories.computeIfPresent(ids.getValue(c.uuid)) { _, old ->
                old.copy(name = c.name)
            }
        }
        for (c in changes.updateFlags) {
            categories.computeIfPresent(ids.getValue(c.uuid)) { _, old -> old.copy(flags = c.flags) }
        }
        changes.order.forEachIndexed { order, uuid ->
            ids[uuid]?.let { id -> categories.computeIfPresent(id) { _, old -> old.copy(order = order.toLong()) } }
        }
        return CategoryApplyResult(ids)
    }

    override suspend fun applyEntries(changes: LocalChangeSet, categoryLocalIds: Map<String, Long>): EntryApplyResult {
        val crash = crashOnApplyCall == ++applyCalls
        fun ids(uuids: List<String>) = uuids.mapNotNull { categoryLocalIds[it]?.toString() }
        for (insert in changes.inserts) {
            if (insert.key in skip) continue
            entries[insert.key] = insert.copy(removedAtMillis = null, categoryUuids = ids(insert.categoryUuids))
        }
        val unrealized = HashMap<EntryKey, EntryRecord>()
        for ((i, update) in changes.updates.withIndex()) {
            if (crash && i >= changes.updates.size / 2) throw Crash()
            val key = update.entry.key
            if (key in skip) continue
            val target = update.entry
            var row = entries.getValue(key)
            for (field in update.fields) {
                row = when (field) {
                    EntryField.FAVORITE -> row.copy(
                        favorite = target.favorite,
                        favoriteAtMillis = if (target.favorite) target.favoriteAtMillis else row.favoriteAtMillis,
                    )
                    EntryField.VIEWER_FLAGS -> row.copy(viewerFlags = target.viewerFlags)
                    EntryField.CHAPTER_FLAGS -> row.copy(chapterFlags = target.chapterFlags)
                    EntryField.NOTES -> row.copy(notes = target.notes)
                    EntryField.CATEGORIES -> row.copy(categoryUuids = ids(target.categoryUuids))
                    EntryField.EXCLUDED_SCANLATORS -> row.copy(excludedScanlators = target.excludedScanlators)
                    EntryField.DEDUP_OVERRIDE -> row.copy(dedupOverride = target.dedupOverride)
                }
            }
            if (key !in unrealize) {
                val states = update.chapters.associateBy { it.url }
                row = row.copy(chapters = row.chapters.map { c -> states[c.url]?.let { c.withState(it) } ?: c })
            }
            val deleted = update.deleteTrackerIds.toSet() + update.upsertTracks.map { it.trackerId }
            row =
                row.copy(
                    tracks = (
                        row.tracks.filter {
                            it.trackerId !in deleted
                        } + update.upsertTracks
                        ).sortedBy { it.trackerId },
                )
            entries[key] = row
            if (key in unrealize && update.chapters.isNotEmpty()) {
                unrealized[key] = read(row, categoryLocalIds.entries.associate { (u, id) -> id to u })
            }
        }
        if (crash) throw Crash()
        val touched = changes.inserts.map { it.key } + changes.updates.map { it.entry.key }
        return EntryApplyResult(skipped = touched.filterTo(HashSet()) { it in skip }, unrealized = unrealized)
    }

    override suspend fun applyPrefs(changes: PrefChangeSet): PrefApplyResult {
        changes.set.forEach { prefs[it.key] = it.value }
        changes.remove.forEach { prefs.remove(it) }
        return PrefApplyResult()
    }

    // --- User edits ---

    fun addEntry(key: EntryKey, chapters: Int, nowMillis: Long, vararg categoryIds: Long) {
        entries[key] = EntryRecord(
            key = key,
            favorite = true,
            favoriteAtMillis = nowMillis,
            title = "Title ${key.url}",
            categoryUuids = categoryIds.map { it.toString() },
            chapters = (1..chapters).map {
                ChapterRecord(url = "${key.url}/c$it", name = "Ch. $it", chapterNumber = it.toDouble())
            },
        )
    }

    fun edit(key: EntryKey, block: (EntryRecord) -> EntryRecord) {
        entries[key] = block(entries.getValue(key))
    }

    fun editChapter(key: EntryKey, url: String, block: (ChapterRecord) -> ChapterRecord) = edit(key) { entry ->
        entry.copy(chapters = entry.chapters.map { if (it.url == url) block(it) else it })
    }

    fun read(key: EntryKey, url: String, atMillis: Long) = editChapter(key, url) {
        it.copy(
            read = true,
            lastPageRead = maxOf(it.lastPageRead, 10),
            historyReadAtMillis = atMillis,
            historyDurationMs = 1000,
        )
    }

    fun setFavorite(key: EntryKey, favorite: Boolean, nowMillis: Long) = edit(key) {
        it.copy(favorite = favorite, favoriteAtMillis = if (favorite) nowMillis else it.favoriteAtMillis)
    }

    fun createCategory(name: String): Long {
        val id = nextCategoryId++
        categories[id] = Category(name, categories.size.toLong())
        return id
    }

    fun deleteCategory(id: Long) {
        categories.remove(id)
        entries.replaceAll { _, e -> e.copy(categoryUuids = e.categoryUuids - id.toString()) }
    }

    fun toggleCategory(key: EntryKey, id: Long) = edit(key) {
        val tag = id.toString()
        it.copy(categoryUuids = if (tag in it.categoryUuids) it.categoryUuids - tag else it.categoryUuids + tag)
    }

    fun categoryId(name: String): Long = categories.entries.first { it.value.name == name }.key

    fun favorites(): Set<EntryKey> = entries.values.filter { it.favorite }.mapTo(HashSet()) { it.key }

    /**
     * What sync must make equal across devices: favorites with their synced user state (categories by name),
     * categories in order, and prefs. favoriteAt is left out: rows that stay favorite keep their own time.
     */
    fun projection(): Map<String, Any> = mapOf(
        "entries" to entries.values.filter { it.favorite }.associate { e ->
            e.key to e.copy(
                favoriteAtMillis = null,
                categoryUuids = e.categoryUuids.map { categories.getValue(it.toLong()).name }.sorted(),
            )
        },
        "categories" to categories.values.sortedBy { it.order }.map { it.name to it.flags },
        "prefs" to prefs.toMap(),
    )
}

private fun ChapterRecord.withState(s: ChapterRecord) = copy(
    read = s.read,
    bookmark = s.bookmark,
    lastPageRead = s.lastPageRead,
    historyReadAtMillis = s.historyReadAtMillis,
    historyDurationMs = s.historyDurationMs,
)

/** One install: its library survives [restart]; the engine and the base store are rebuilt from disk. */
class SimDevice(val name: String, val backend: FakeRemoteStore.Backend, private val dir: Path, val clock: () -> Long) {
    val library = FakeLibrary()
    var key: SyncKey? = null
    val logs = ArrayList<String>()

    /** Called before every base store file operation; throw [Crash] to die there. */
    var storeCrash: (String) -> Unit = {}

    lateinit var store: BaseStore
        private set
    lateinit var engine: SyncEngine
        private set

    init {
        Files.createDirectories(dir)
        restart()
    }

    fun restart() {
        store = BaseStore.open(dir, { storeCrash(it) }, fsync = false)
        engine = SyncEngine(
            remote = FakeRemoteStore(LAYOUT, backend),
            layout = LAYOUT,
            store = store,
            local = library,
            applier = library,
            device = SyncDevice(name, "Sim $name"),
            clock = clock,
            listener = object : SyncListener {
                override fun onLog(message: String) {
                    logs += message
                }
            },
        )
    }

    suspend fun sync(
        confirm: Set<EntryKey> = emptySet(),
        reject: Set<EntryKey> = emptySet(),
        newEpoch: Boolean = false,
    ): SyncOutcome = engine.sync(SyncRequest(key, null, confirm, reject, newEpoch))

    /** Syncs and expects success. */
    suspend fun synced(confirm: Set<EntryKey> = emptySet(), reject: Set<EntryKey> = emptySet()): SyncOutcome.Synced {
        val outcome = sync(confirm, reject)
        check(outcome is SyncOutcome.Synced) { "$name: $outcome" }
        return outcome
    }
}

/** Devices sharing one remote, a clock that ticks on every read, and a temp directory. */
class SimWorld(private val root: Path) {
    val backend = FakeRemoteStore.Backend()
    var nowMillis = 1_800_000_000_000L
    val devices = ArrayList<SimDevice>()

    fun device(name: String) = SimDevice(name, backend, root.resolve(name), { nowMillis++ }).also { devices += it }

    /** Syncs every device until a full round commits nothing; fails if that takes more than [rounds] rounds. */
    suspend fun settle(rounds: Int = 5) {
        repeat(rounds) {
            val committed = devices.map { it.synced().committed }
            if (committed.none { it }) return
        }
        error("No quiescence after $rounds rounds")
    }

    fun payloadIds(): Set<String> = backend.files().filter { it.name != LAYOUT.manifestName }.mapTo(HashSet()) { it.id }

    fun manifest() = backend.files().single { it.name == LAYOUT.manifestName }
}

fun key(n: Int) = EntryKey(sourceId = 100, url = "/manga/$n")
