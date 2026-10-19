package haruka.sync.store

import haruka.sync.codec.SyncCodec
import haruka.sync.model.BUCKET_COUNT
import haruka.sync.model.CategoriesPayload
import haruka.sync.model.ManifestBody
import haruka.sync.model.ManifestHeader
import haruka.sync.model.PrefsPayload
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
import java.nio.file.StandardOpenOption.WRITE

/**
 * The fork-owned three-way merge base of one remote namespace, under `filesDir/haruka/sync/<namespace>/`:
 * - immutable snapshot directories `base-<commitSeq>-<n>/` holding the last applied state (manifest header and
 *   body, all [BUCKET_COUNT] buckets with their `localPresent` flags, categories, prefs);
 * - `CURRENT`, naming the active snapshot;
 * - `state.json`, the [SyncState].
 *
 * Root files are replaced atomically (temp file, fsync, `ATOMIC_MOVE`, directory fsync), so a crash at any point
 * leaves the old or the new version, never a mix. A snapshot is [stage]d, patched while the DB apply runs, and
 * becomes the base only through [activate]; [open] deletes every snapshot `CURRENT` does not name and every temp
 * file, which is all an interrupted write can leave behind.
 *
 * Not thread-safe: the caller runs one sync at a time.
 */
class BaseStore private constructor(
    val directory: Path,
    /** Called before every file-system mutation; tests throw from it to simulate a crash at that point. */
    private val crashPoint: (String) -> Unit,
    /** Off only in crash tests: a simulated process death keeps the page cache, so fsync changes nothing there. */
    private val fsync: Boolean,
) {

    /** The active snapshot, or null when there is no base (first sync on this device: union mode). */
    var current: BaseSnapshot? = null
        private set

    fun readState(): SyncState {
        val file = directory.resolve(STATE_FILE)
        if (!Files.exists(file)) return SyncState()
        return json.decodeFromString(SyncState.serializer(), Files.readAllBytes(file).decodeToString())
    }

    fun writeState(state: SyncState) {
        writeAtomically(
            directory.resolve(STATE_FILE),
            json.encodeToString(SyncState.serializer(), state).encodeToByteArray(),
        )
        syncDirectory(directory, fsync)
    }

    /**
     * Starts a new snapshot for the head described by [header] and [body]. Every bucket, the categories and the
     * prefs must be written (or carried over) before [activate] accepts it.
     */
    fun stage(header: ManifestHeader, body: ManifestBody): StagedSnapshot {
        require(header.commitSeq >= 0) { "Negative commitSeq ${header.commitSeq}" }
        val n = snapshotNames()
            .mapNotNull { SNAPSHOT_NAME.matchEntire(it) }
            .filter { it.groupValues[1].toLong() == header.commitSeq }
            .maxOfOrNull { it.groupValues[2].toInt() + 1 }
            ?: 0
        val name = "base-${header.commitSeq}-$n"
        crashPoint("create $name")
        val staged = StagedSnapshot(this, Files.createDirectory(directory.resolve(name)))
        writeAtomically(staged.directory.resolve(HEADER_FILE), SyncCodec.encodeManifestHeader(header))
        writeAtomically(staged.directory.resolve(BODY_FILE), SyncCodec.encodeManifestBody(body))
        return staged
    }

    /**
     * Makes [staged] the base by atomically replacing `CURRENT`, then deletes every other snapshot, including the
     * previous base: a [BaseSnapshot] obtained before this call must not be read afterwards.
     */
    fun activate(staged: StagedSnapshot): BaseSnapshot {
        require(staged.store === this) { "Snapshot ${staged.name} belongs to another store" }
        check(!staged.activated) { "Snapshot ${staged.name} is already active" }
        val missing = SNAPSHOT_FILES.filterNot { Files.isRegularFile(staged.directory.resolve(it)) }
        // An incomplete base would read as deletions on the next merge.
        check(missing.isEmpty()) { "Snapshot ${staged.name} is incomplete, missing $missing" }
        val snapshot = BaseSnapshot.read(staged.directory)
        // The snapshot's renames must be durable before CURRENT may name it.
        syncDirectory(staged.directory, fsync)
        writeAtomically(directory.resolve(CURRENT_FILE), staged.name.encodeToByteArray())
        syncDirectory(directory, fsync)
        staged.activated = true
        current = snapshot
        deleteSnapshotsExcept(staged.name)
        return snapshot
    }

    internal fun writeAtomically(target: Path, bytes: ByteArray) {
        val temp = target.resolveSibling(target.fileName.toString() + TEMP_SUFFIX)
        crashPoint("write ${directory.relativize(target)}")
        FileChannel.open(temp, WRITE, CREATE, TRUNCATE_EXISTING).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            if (fsync) channel.force(true)
        }
        crashPoint("move ${directory.relativize(target)}")
        Files.move(temp, target, ATOMIC_MOVE)
    }

    private fun recover() {
        Files.newDirectoryStream(directory, "*$TEMP_SUFFIX").use { it.toList() }.forEach {
            crashPoint("delete ${it.fileName}")
            Files.delete(it)
        }
        val currentFile = directory.resolve(CURRENT_FILE)
        val named = if (Files.exists(currentFile)) Files.readAllBytes(currentFile).decodeToString() else null
        val active = named?.takeIf { SNAPSHOT_NAME.matches(it) && Files.isDirectory(directory.resolve(it)) }
        if (named != null && active == null) {
            // Unreachable through this class; with nothing to point at, no base (union mode) is the safe reading.
            crashPoint("delete $CURRENT_FILE")
            Files.delete(currentFile)
            syncDirectory(directory, fsync)
        }
        deleteSnapshotsExcept(active)
        current = active?.let { BaseSnapshot.read(directory.resolve(it)) }
    }

    private fun snapshotNames(): List<String> = Files.newDirectoryStream(directory).use { entries ->
        entries.filter { Files.isDirectory(it) && SNAPSHOT_NAME.matches(it.fileName.toString()) }
            .map { it.fileName.toString() }
    }

    private fun deleteSnapshotsExcept(keep: String?) {
        for (name in snapshotNames()) {
            if (name == keep) continue
            val dir = directory.resolve(name)
            Files.newDirectoryStream(dir).use { it.toList() }.forEach {
                crashPoint("delete $name/${it.fileName}")
                Files.delete(it)
            }
            crashPoint("delete $name")
            Files.delete(dir)
        }
    }

    companion object {
        /** Opens the store of [namespace] (the applicationId, so dev builds keep their own base) under [filesDir]. */
        fun open(filesDir: Path, namespace: String): BaseStore {
            require(NAMESPACE.matches(namespace) && namespace != "." && namespace != "..") {
                "Invalid sync namespace: $namespace"
            }
            return open(filesDir.resolve("haruka").resolve("sync").resolve(namespace), crashPoint = {}, fsync = true)
        }

        internal fun open(directory: Path, crashPoint: (String) -> Unit, fsync: Boolean): BaseStore {
            Files.createDirectories(directory)
            return BaseStore(directory, crashPoint, fsync).apply { recover() }
        }
    }
}

/** An activated, immutable base snapshot. [header] and [body] describe the head it was merged with. */
class BaseSnapshot private constructor(
    val name: String,
    internal val directory: Path,
    val header: ManifestHeader,
    val body: ManifestBody,
) {
    fun readBucket(index: Int): BaseBucket = readBucketFile(directory, index)

    fun readCategories(): CategoriesPayload = SyncCodec.decodeCategories(
        readGzipped(directory.resolve(CATEGORIES_FILE)),
    )

    fun readPrefs(): PrefsPayload = SyncCodec.decodePrefs(readGzipped(directory.resolve(PREFS_FILE)))

    internal companion object {
        fun read(directory: Path) = BaseSnapshot(
            name = directory.fileName.toString(),
            directory = directory,
            header = SyncCodec.decodeManifestHeader(Files.readAllBytes(directory.resolve(HEADER_FILE))),
            body = SyncCodec.decodeManifestBody(Files.readAllBytes(directory.resolve(BODY_FILE))),
        )
    }
}

/**
 * A snapshot being written. Files may be rewritten until [BaseStore.activate]; each write replaces the file
 * atomically, so a patch never touches the bytes of the snapshot a bucket was carried from.
 */
class StagedSnapshot internal constructor(
    internal val store: BaseStore,
    internal val directory: Path,
) {
    val name: String = directory.fileName.toString()

    internal var activated = false

    fun writeBucket(bucket: BaseBucket) {
        checkWritable()
        val bytes = SyncCodec.gzip(protoBuf.encodeToByteArray(BaseBucket.serializer(), bucket.canonical()))
        store.writeAtomically(directory.resolve(bucketFileName(bucket.index)), bytes)
    }

    /** Copies bucket [index] unchanged from [from], without decoding it. */
    fun carryBucket(from: BaseSnapshot, index: Int) {
        checkWritable()
        val fileName = bucketFileName(index)
        store.writeAtomically(directory.resolve(fileName), Files.readAllBytes(from.directory.resolve(fileName)))
    }

    fun readBucket(index: Int): BaseBucket = readBucketFile(directory, index)

    fun writeCategories(payload: CategoriesPayload) {
        checkWritable()
        store.writeAtomically(directory.resolve(CATEGORIES_FILE), SyncCodec.gzip(SyncCodec.encodeCategories(payload)))
    }

    fun writePrefs(payload: PrefsPayload) {
        checkWritable()
        store.writeAtomically(directory.resolve(PREFS_FILE), SyncCodec.gzip(SyncCodec.encodePrefs(payload)))
    }

    private fun checkWritable() = check(!activated) { "Snapshot $name is active and immutable" }
}

private const val CURRENT_FILE = "CURRENT"
private const val STATE_FILE = "state.json"
private const val TEMP_SUFFIX = ".tmp"
private const val HEADER_FILE = "header.pb"
private const val BODY_FILE = "body.pb"
private const val CATEGORIES_FILE = "categories.pb.gz"
private const val PREFS_FILE = "prefs.pb.gz"

private val SNAPSHOT_NAME = Regex("base-(\\d+)-(\\d+)")
private val NAMESPACE = Regex("[A-Za-z0-9._-]+")

private val SNAPSHOT_FILES =
    listOf(HEADER_FILE, BODY_FILE, CATEGORIES_FILE, PREFS_FILE) + (0..<BUCKET_COUNT).map(::bucketFileName)

private val protoBuf = ProtoBuf { encodeDefaults = false }
private val json = Json { ignoreUnknownKeys = true }

private fun bucketFileName(index: Int): String {
    require(index in 0..<BUCKET_COUNT) { "Bucket index $index out of range" }
    return "b${index.toString().padStart(3, '0')}.pb.gz"
}

private fun readBucketFile(directory: Path, index: Int): BaseBucket {
    val bucket = protoBuf.decodeFromByteArray(
        BaseBucket.serializer(),
        readGzipped(directory.resolve(bucketFileName(index))),
    )
    check(bucket.index == index) { "${directory.fileName}/${bucketFileName(index)} holds bucket ${bucket.index}" }
    return bucket
}

private fun readGzipped(file: Path): ByteArray = SyncCodec.gunzip(Files.readAllBytes(file))

private fun syncDirectory(directory: Path, fsync: Boolean) {
    if (fsync) FileChannel.open(directory, READ).use { it.force(true) }
}
