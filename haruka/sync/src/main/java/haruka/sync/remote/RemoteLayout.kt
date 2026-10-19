package haruka.sync.remote

import haruka.sync.model.BUCKET_COUNT

/** What a remote file holds; written to the `kind` app property. */
enum class RemoteKind(val wire: String) {
    MANIFEST("manifest"),
    BUCKET("bucket"),
    CATEGORIES("categories"),
    PREFS("prefs"),
}

/** A remote file identified by its name. Payloads carry the first 16 hex digits of the sha of their stored bytes. */
sealed interface RemoteFile {
    val kind: RemoteKind

    /** The single mutable file; every commit replaces its content through compare-and-swap. */
    data object Manifest : RemoteFile {
        override val kind = RemoteKind.MANIFEST
    }

    /**
     * An immutable payload. The name covers the stored bytes (gzip, then encryption), so the same records stored
     * under a new key get a new name and never overwrite a file another device may still be reading.
     */
    sealed interface Payload : RemoteFile {
        val sha16: String
        val encrypted: Boolean
    }

    data class Bucket(val index: Int, override val sha16: String, override val encrypted: Boolean) : Payload {
        override val kind get() = RemoteKind.BUCKET

        init {
            require(index in 0..<BUCKET_COUNT) { "Bucket index out of range: $index" }
            requireSha16(sha16)
        }
    }

    data class Categories(override val sha16: String, override val encrypted: Boolean) : Payload {
        override val kind get() = RemoteKind.CATEGORIES

        init {
            requireSha16(sha16)
        }
    }

    data class Prefs(override val sha16: String, override val encrypted: Boolean) : Payload {
        override val kind get() = RemoteKind.PREFS

        init {
            requireSha16(sha16)
        }
    }

    companion object {
        fun bucket(index: Int, storedSha: String, encrypted: Boolean) = Bucket(index, sha16Of(storedSha), encrypted)

        fun categories(storedSha: String, encrypted: Boolean) = Categories(sha16Of(storedSha), encrypted)

        fun prefs(storedSha: String, encrypted: Boolean) = Prefs(sha16Of(storedSha), encrypted)

        private fun sha16Of(storedSha: String): String {
            require(SHA256_HEX.matches(storedSha)) { "Not a lowercase hex SHA-256: $storedSha" }
            return storedSha.substring(0, 16)
        }

        private fun requireSha16(sha16: String) =
            require(SHA16_HEX.matches(sha16)) { "Not 16 lowercase hex digits: $sha16" }

        private val SHA256_HEX = Regex("[0-9a-f]{64}")
        private val SHA16_HEX = Regex("[0-9a-f]{16}")
    }
}

/**
 * Names and app properties of the files one app install family keeps in Drive `appDataFolder`.
 *
 * [namespace] is the applicationId, so `app.haruka.dev` and `app.haruka` never read or delete each other's files
 * even on the same Google account: listings filter on the `ns` app property, and every name starts with
 * `<namespace>-`. An applicationId cannot contain `-`, so one namespace is never a name prefix of another.
 */
class RemoteLayout(val namespace: String) {

    init {
        require(APPLICATION_ID.matches(namespace)) { "Not an applicationId: $namespace" }
    }

    private val prefix = "$namespace-"

    val manifestName = prefix + MANIFEST

    fun nameOf(file: RemoteFile): String = when (file) {
        RemoteFile.Manifest -> manifestName
        is RemoteFile.Bucket -> prefix + "b" + file.index.toString().padStart(3, '0') + payloadSuffix(file)
        is RemoteFile.Categories -> prefix + CATEGORIES + payloadSuffix(file)
        is RemoteFile.Prefs -> prefix + PREFS + payloadSuffix(file)
    }

    /** The file [name] denotes in this namespace, or null for names of other namespaces or unknown shapes. */
    fun parse(name: String): RemoteFile? {
        if (!name.startsWith(prefix)) return null
        val rest = name.substring(prefix.length)
        if (rest == MANIFEST) return RemoteFile.Manifest
        val match = PAYLOAD_NAME.matchEntire(rest) ?: return null
        val (type, sha16, enc) = match.destructured
        val encrypted = enc.isNotEmpty()
        return when (type) {
            CATEGORIES -> RemoteFile.Categories(sha16, encrypted)
            PREFS -> RemoteFile.Prefs(sha16, encrypted)
            else -> {
                val index = type.substring(1).toInt()
                if (index < BUCKET_COUNT) RemoteFile.Bucket(index, sha16, encrypted) else null
            }
        }
    }

    /** Drive `appProperties` written on every file this layout names. */
    fun appProperties(file: RemoteFile): Map<String, String> =
        mapOf(PROPERTY_NAMESPACE to namespace, PROPERTY_KIND to file.kind.wire)

    private fun payloadSuffix(file: RemoteFile.Payload) =
        "-" + file.sha16 + PAYLOAD_EXTENSION + if (file.encrypted) ENCRYPTED_EXTENSION else ""

    companion object {
        /** App property holding [namespace]; Drive listings query `appProperties has {key='ns' and value=…}`. */
        const val PROPERTY_NAMESPACE = "ns"

        /** App property holding [RemoteKind.wire]. */
        const val PROPERTY_KIND = "kind"

        private const val MANIFEST = "manifest"
        private const val CATEGORIES = "categories"
        private const val PREFS = "prefs"
        private const val PAYLOAD_EXTENSION = ".pb.gz"
        private const val ENCRYPTED_EXTENSION = ".enc"

        // Android applicationId: two or more dot-separated segments, each a letter followed by letters, digits or _.
        private val APPLICATION_ID = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
        private val PAYLOAD_NAME = Regex("(b\\d{3}|$CATEGORIES|$PREFS)-([0-9a-f]{16})\\.pb\\.gz(\\.enc)?")
    }
}
