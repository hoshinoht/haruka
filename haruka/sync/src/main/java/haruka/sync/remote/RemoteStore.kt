package haruka.sync.remote

import java.io.IOException

/**
 * The remote half of the commit protocol: immutable payloads plus one mutable manifest replaced only through
 * compare-and-swap. Every method works within one [RemoteLayout] namespace; files of other namespaces are never
 * listed, read or written.
 *
 * Failures other than the typed results below are thrown as [IOException]: [RemoteFileGoneException] when a file
 * the caller expected no longer exists (restart from [readHead]), [RemoteHttpException] when the remote refused a
 * request and retrying did not help, any other [IOException] when the connection kept failing.
 */
interface RemoteStore {

    /** Every file of the namespace, manifests included. */
    suspend fun list(): List<RemoteRef>

    /** The stored bytes of [fileId]; throws [RemoteFileGoneException] if it no longer exists. */
    suspend fun download(fileId: String): ByteArray

    /** Creates a new payload file and returns its id. Never replaces an existing file. */
    suspend fun createImmutable(file: RemoteFile.Payload, bytes: ByteArray): String

    /**
     * The current manifest, or null if the remote has none. If several exist (two devices initialized an empty
     * remote at once), the oldest wins: lowest [RemoteRef.createdAtMillis], then lowest id.
     *
     * The version tag is read before the content. A commit landing in between leaves a tag older than the
     * content, so [casHead] with this head fails with [CasResult.Conflict] instead of overwriting that commit.
     */
    suspend fun readHead(): Head?

    /**
     * Replaces the manifest with [bytes] if it is still [expected]. With [expected] null it creates the manifest
     * if the remote has none, and reports [CasResult.Conflict] if one exists.
     */
    suspend fun casHead(expected: Head?, bytes: ByteArray): CasResult

    /** Deletes [fileId]; a file that is already gone counts as deleted. */
    suspend fun delete(fileId: String)
}

/** One file of the namespace as the remote lists it. */
data class RemoteRef(
    val id: String,
    val name: String,
    val sizeBytes: Long,
    /** Server-side creation time: orders duplicate manifests and lets the sweep skip payloads still uploading. */
    val createdAtMillis: Long,
)

/** The manifest as read: its file, the version tag read before the content, and the content. */
class Head(val fileId: String, val etag: String, val bytes: ByteArray) {

    // Hand-written because data class equality compares ByteArray by reference.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        return other is Head && fileId == other.fileId && etag == other.etag && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int = (31 * fileId.hashCode() + etag.hashCode()) * 31 + bytes.contentHashCode()

    override fun toString(): String = "Head(fileId=$fileId, etag=$etag, bytes=${bytes.size} bytes)"
}

sealed interface CasResult {

    /** The new manifest is the head. */
    data object Committed : CasResult

    /** The head is no longer the expected one (or a manifest appeared); nothing was written. Re-read the head. */
    data object Conflict : CasResult

    /**
     * The request may or may not have been applied (connection lost or server error after sending). It is never
     * retried blindly: the next sync recognises its own commit by lineage (`pendingCommitId`).
     */
    data class Ambiguous(val cause: Throwable) : CasResult
}

/** A file the caller expected is gone, typically a payload superseded and deleted by another device's commit. */
class RemoteFileGoneException(val fileId: String) : IOException("Remote file $fileId no longer exists")

/** The remote refused a request ([code], with the provider's [reason] if it gave one) and retrying did not help. */
class RemoteHttpException(val code: Int, val reason: String?) :
    IOException("Remote request failed: HTTP $code" + if (reason != null) " ($reason)" else "")
