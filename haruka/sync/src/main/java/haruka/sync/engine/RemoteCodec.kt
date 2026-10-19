package haruka.sync.engine

import haruka.sync.codec.SyncCodec
import haruka.sync.crypto.PayloadSlot
import haruka.sync.crypto.SyncCryptoException
import haruka.sync.crypto.SyncKey
import haruka.sync.model.Bucket
import haruka.sync.model.CategoriesPayload
import haruka.sync.model.PayloadRef
import haruka.sync.model.PrefsPayload
import haruka.sync.remote.Manifest
import haruka.sync.remote.ManifestFile
import haruka.sync.remote.RemoteFile
import haruka.sync.remote.RemoteLayout
import java.io.IOException

/** Remote bytes that can't be read; the engine stops with `SyncError.Corrupt`. */
internal class CorruptRemoteException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** A payload ready to upload: its file name, stored bytes and the shas the manifest records. */
internal class EncodedPayload(
    val file: RemoteFile.Payload,
    val stored: ByteArray,
    val storedSha: String,
    val plaintextSha: String,
    val entryCount: Int,
) {
    fun ref(fileId: String, layout: RemoteLayout) =
        PayloadRef(fileId, layout.nameOf(file), storedSha, plaintextSha, entryCount)
}

/**
 * Stored form of payloads and manifests: canonical protobuf, gzip, then AES-GCM under [key] when the remote is
 * encrypted. Reading verifies both shas the manifest records, so a payload that was replaced, truncated or swapped
 * is reported as corrupt instead of being merged.
 */
internal class RemoteCodec(private val layout: RemoteLayout, private val key: SyncKey?) {

    fun encodeBucket(bucket: Bucket): EncodedPayload =
        encode(PayloadSlot.Bucket(bucket.index), SyncCodec.encodeBucket(bucket), bucket.entries.size) { sha, enc ->
            RemoteFile.bucket(bucket.index, sha, enc)
        }

    fun encodeCategories(payload: CategoriesPayload): EncodedPayload =
        encode(PayloadSlot.Categories, SyncCodec.encodeCategories(payload), payload.categories.size) { sha, enc ->
            RemoteFile.categories(sha, enc)
        }

    fun encodePrefs(payload: PrefsPayload): EncodedPayload =
        encode(PayloadSlot.Prefs, SyncCodec.encodePrefs(payload), payload.prefs.size) { sha, enc ->
            RemoteFile.prefs(sha, enc)
        }

    fun decodeBucket(index: Int, ref: PayloadRef, stored: ByteArray): Bucket {
        val bucket = decode(ref) { SyncCodec.decodeBucket(plaintext(PayloadSlot.Bucket(index), ref, stored)) }
        if (bucket.index != index) throw CorruptRemoteException("${ref.name} holds bucket ${bucket.index}")
        return bucket
    }

    fun decodeCategories(ref: PayloadRef, stored: ByteArray): CategoriesPayload =
        decode(ref) { SyncCodec.decodeCategories(plaintext(PayloadSlot.Categories, ref, stored)) }

    fun decodePrefs(ref: PayloadRef, stored: ByteArray): PrefsPayload =
        decode(ref) { SyncCodec.decodePrefs(plaintext(PayloadSlot.Prefs, ref, stored)) }

    /**
     * The payload [ref] names, verified like a decode, stored again by [target] (another key, or plaintext). The
     * gzipped records are reused as they are, so the plaintext sha and entry count stay those of [ref].
     */
    fun reencode(slot: PayloadSlot, ref: PayloadRef, stored: ByteArray, target: RemoteCodec): EncodedPayload {
        val gzipped = decode(ref) { opened(slot, ref, stored).also { checkPlaintext(ref, SyncCodec.gunzip(it)) } }
        return target.store(slot, gzipped, ref.plaintextSha, ref.entryCount) { sha, encrypted ->
            when (slot) {
                is PayloadSlot.Bucket -> RemoteFile.bucket(slot.index, sha, encrypted)
                PayloadSlot.Categories -> RemoteFile.categories(sha, encrypted)
                PayloadSlot.Prefs -> RemoteFile.prefs(sha, encrypted)
            }
        }
    }

    /** The envelope and plaintext header only; the header checks run before [openBody]. */
    fun readManifestFile(bytes: ByteArray): ManifestFile = try {
        ManifestFile.decode(bytes)
    } catch (e: IllegalArgumentException) {
        // SerializationException is an IllegalArgumentException.
        throw CorruptRemoteException("Manifest does not decode", e)
    }

    /** Decrypts (when encrypted) and decodes the body; the header checks must have accepted [file]'s key. */
    fun openBody(file: ManifestFile): Manifest = decode(null) {
        if (file.header.encryption == null) file.decodePlaintext() else requireKey().decryptManifest(layout, file)
    }

    fun encodeManifest(manifest: Manifest): ByteArray =
        (key?.encryptManifest(layout, manifest) ?: ManifestFile.plaintext(manifest)).encode()

    private inline fun encode(
        slot: PayloadSlot,
        plaintext: ByteArray,
        entryCount: Int,
        file: (storedSha: String, encrypted: Boolean) -> RemoteFile.Payload,
    ): EncodedPayload = store(slot, SyncCodec.gzip(plaintext), SyncCodec.sha256Hex(plaintext), entryCount, file)

    private inline fun store(
        slot: PayloadSlot,
        gzipped: ByteArray,
        plaintextSha: String,
        entryCount: Int,
        file: (storedSha: String, encrypted: Boolean) -> RemoteFile.Payload,
    ): EncodedPayload {
        val stored = key?.encryptPayload(layout, slot, gzipped) ?: gzipped
        val storedSha = SyncCodec.sha256Hex(stored)
        return EncodedPayload(file(storedSha, key != null), stored, storedSha, plaintextSha, entryCount)
    }

    private fun plaintext(slot: PayloadSlot, ref: PayloadRef, stored: ByteArray): ByteArray =
        SyncCodec.gunzip(opened(slot, ref, stored)).also { checkPlaintext(ref, it) }

    /** The gzipped records of [stored] once its stored sha, its slot and (if encrypted) the key check out. */
    private fun opened(slot: PayloadSlot, ref: PayloadRef, stored: ByteArray): ByteArray {
        if (SyncCodec.sha256Hex(stored) != ref.storedSha) {
            throw CorruptRemoteException("${ref.name} does not match its stored sha")
        }
        val file = layout.parse(ref.name) as? RemoteFile.Payload
            ?: throw CorruptRemoteException("${ref.name} is not a payload name")
        if (file.kind != slot.kind) throw CorruptRemoteException("${ref.name} referenced as ${slot.kind.wire}")
        return if (file.encrypted) requireKey().decryptPayload(layout, slot, stored) else stored
    }

    private fun checkPlaintext(ref: PayloadRef, plaintext: ByteArray) {
        if (SyncCodec.sha256Hex(plaintext) != ref.plaintextSha) {
            throw CorruptRemoteException("${ref.name} does not match its plaintext sha")
        }
    }

    // A plaintext manifest naming encrypted payloads (or the reverse) is as unreadable as a flipped bit.
    private fun requireKey(): SyncKey = key ?: throw CorruptRemoteException("Encrypted remote data without a key")

    private inline fun <T> decode(ref: PayloadRef?, block: () -> T): T {
        val what = ref?.name ?: "Manifest body"
        return try {
            block()
        } catch (e: SyncCryptoException) {
            throw CorruptRemoteException("$what failed authentication", e)
        } catch (e: IllegalArgumentException) {
            // SerializationException is an IllegalArgumentException.
            throw CorruptRemoteException("$what does not decode", e)
        } catch (e: IOException) {
            // Only gunzip of bytes already in memory throws here; network errors happen before decoding.
            throw CorruptRemoteException("$what does not decompress", e)
        }
    }
}
