package haruka.sync.remote

import java.io.IOException

/**
 * In-memory [RemoteStore] with the semantics of the Drive store: version tags change on every write, the oldest
 * of duplicate manifests is the head, and the tag is read before the content.
 *
 * Stores built on one [Backend] share its files, like devices signed into one account. Tests inject behaviour
 * through the backend: [Backend.intercept] runs before every step (throw from it to fail the step, write through
 * another store or the backend to interleave a foreign change), [Backend.nextCasAmbiguous] makes the next CAS
 * report [CasResult.Ambiguous], and [Backend.remove] makes a file disappear (404) behind a store's back.
 */
class FakeRemoteStore(private val layout: RemoteLayout, val backend: Backend = Backend()) : RemoteStore {

    enum class Step { LIST, DOWNLOAD, CREATE, READ_HEAD_TAG, READ_HEAD_CONTENT, CAS, DELETE }

    class StoredFile(
        val id: String,
        val name: String,
        val appProperties: Map<String, String>,
        val bytes: ByteArray,
        val createdAtMillis: Long,
        val generation: Long,
    ) {
        val etag get() = "\"$id-$generation\""
    }

    class Backend {
        private val files = LinkedHashMap<String, StoredFile>()
        private var lastId = 0
        private var lastGeneration = 0L
        private var clockMillis = 0L

        /** Runs before each step with the file id it touches (null when it touches none yet). */
        var intercept: suspend (step: Step, fileId: String?) -> Unit = { _, _ -> }

        /**
         * Makes the next CAS report [CasResult.Ambiguous]: true applies the write first (the response was lost),
         * false drops it (the request was lost). Cleared once used.
         */
        var nextCasAmbiguous: Boolean? = null

        @Synchronized
        fun files(): List<StoredFile> = files.values.toList()

        @Synchronized
        fun file(fileId: String): StoredFile? = files[fileId]

        /** Adds a file directly, bypassing every check (e.g. a duplicate manifest from a create race). */
        @Synchronized
        fun add(
            name: String,
            appProperties: Map<String, String>,
            bytes: ByteArray,
            createdAtMillis: Long = nextClock(),
        ): String {
            val id = "f" + (++lastId).toString().padStart(4, '0')
            files[id] = StoredFile(id, name, appProperties, bytes, createdAtMillis, ++lastGeneration)
            return id
        }

        /** Replaces a file's content directly, as another device's committed write would. */
        @Synchronized
        fun overwrite(fileId: String, bytes: ByteArray) {
            val file = checkNotNull(files[fileId]) { "No file $fileId" }
            files[fileId] =
                StoredFile(file.id, file.name, file.appProperties, bytes, file.createdAtMillis, ++lastGeneration)
        }

        @Synchronized
        fun remove(fileId: String): Boolean = files.remove(fileId) != null

        @Synchronized
        internal fun replaceIf(fileId: String, etag: String, bytes: ByteArray): CasResult {
            val file = files[fileId]
            if (file == null || file.etag != etag) return CasResult.Conflict
            overwrite(fileId, bytes)
            return CasResult.Committed
        }

        @Synchronized
        internal fun createIfAbsent(layout: RemoteLayout, bytes: ByteArray): CasResult {
            if (manifests(layout).isNotEmpty()) return CasResult.Conflict
            add(layout.manifestName, layout.appProperties(RemoteFile.Manifest), bytes)
            return CasResult.Committed
        }

        @Synchronized
        internal fun manifests(layout: RemoteLayout): List<StoredFile> = files.values.filter {
            it.appProperties[RemoteLayout.PROPERTY_NAMESPACE] == layout.namespace &&
                it.appProperties[RemoteLayout.PROPERTY_KIND] == RemoteKind.MANIFEST.wire
        }

        private fun nextClock(): Long {
            clockMillis += 1_000
            return clockMillis
        }
    }

    override suspend fun list(): List<RemoteRef> {
        backend.intercept(Step.LIST, null)
        return backend.files()
            .filter { it.appProperties[RemoteLayout.PROPERTY_NAMESPACE] == layout.namespace }
            .map { RemoteRef(it.id, it.name, it.bytes.size.toLong(), it.createdAtMillis) }
    }

    override suspend fun download(fileId: String): ByteArray {
        backend.intercept(Step.DOWNLOAD, fileId)
        return backend.file(fileId)?.bytes?.copyOf() ?: throw RemoteFileGoneException(fileId)
    }

    override suspend fun createImmutable(file: RemoteFile.Payload, bytes: ByteArray): String {
        backend.intercept(Step.CREATE, null)
        return backend.add(layout.nameOf(file), layout.appProperties(file), bytes.copyOf())
    }

    override suspend fun readHead(): Head? {
        val manifest = backend.manifests(layout)
            .minWithOrNull(compareBy<StoredFile> { it.createdAtMillis }.thenBy { it.id })
            ?: return null
        backend.intercept(Step.READ_HEAD_TAG, manifest.id)
        val etag = backend.file(manifest.id)?.etag ?: throw RemoteFileGoneException(manifest.id)
        backend.intercept(Step.READ_HEAD_CONTENT, manifest.id)
        val bytes = backend.file(manifest.id)?.bytes?.copyOf() ?: throw RemoteFileGoneException(manifest.id)
        return Head(manifest.id, etag, bytes)
    }

    override suspend fun casHead(expected: Head?, bytes: ByteArray): CasResult {
        backend.intercept(Step.CAS, expected?.fileId)
        val ambiguous = backend.nextCasAmbiguous
        backend.nextCasAmbiguous = null
        if (ambiguous == false) return CasResult.Ambiguous(IOException("Injected: request lost"))
        val result = if (expected == null) {
            backend.createIfAbsent(layout, bytes.copyOf())
        } else {
            backend.replaceIf(expected.fileId, expected.etag, bytes.copyOf())
        }
        return if (ambiguous == true) CasResult.Ambiguous(IOException("Injected: response lost")) else result
    }

    override suspend fun delete(fileId: String) {
        backend.intercept(Step.DELETE, fileId)
        backend.remove(fileId)
    }
}
