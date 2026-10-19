package haruka.sync.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/** Highest manifest format this build reads and writes. */
const val FORMAT_VERSION = 1

/**
 * The manifest's plaintext part, readable before decryption so format, key and rollback checks run first.
 * Manifest fields have no defaults apart from null, so every set value is written.
 */
@Serializable
data class ManifestHeader(
    @ProtoNumber(1) val formatVersion: Int,
    /** Changes only on "Reset remote" and on the RemoteRolledBack recovery upload. */
    @ProtoNumber(2) val remoteEpoch: String,
    /** Monotonic across commits within an epoch. */
    @ProtoNumber(3) val commitSeq: Long,
    /** Null when the remote is plaintext. */
    @ProtoNumber(4) val encryption: EncryptionParams? = null,
)

/** How the body and payloads are encrypted; [keyCheck] lets a device tell a wrong passphrase from corruption. */
@Serializable
class EncryptionParams(
    @ProtoNumber(1) val keyId: String,
    @ProtoNumber(2) val kdfAlgorithm: String,
    @ProtoNumber(3) val kdfIterations: Int,
    @ProtoNumber(4) val kdfSalt: ByteArray,
    @ProtoNumber(5) val keyCheck: ByteArray,
) {
    // Hand-written because data class equality compares ByteArray by reference.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        return other is EncryptionParams &&
            keyId == other.keyId &&
            kdfAlgorithm == other.kdfAlgorithm &&
            kdfIterations == other.kdfIterations &&
            kdfSalt.contentEquals(other.kdfSalt) &&
            keyCheck.contentEquals(other.keyCheck)
    }

    override fun hashCode(): Int {
        var result = keyId.hashCode()
        result = 31 * result + kdfAlgorithm.hashCode()
        result = 31 * result + kdfIterations
        result = 31 * result + kdfSalt.contentHashCode()
        result = 31 * result + keyCheck.contentHashCode()
        return result
    }

    override fun toString(): String =
        "EncryptionParams(keyId=$keyId, kdfAlgorithm=$kdfAlgorithm, kdfIterations=$kdfIterations, " +
            "kdfSalt=${kdfSalt.toHexString()}, keyCheck=${keyCheck.toHexString()})"
}

/** The manifest's body; encrypted when end-to-end encryption is on. */
@Serializable
data class ManifestBody(
    @ProtoNumber(1) val commitId: String,
    @ProtoNumber(2) val writerDeviceId: String,
    @ProtoNumber(3) val writtenAtMillis: Long,
    @ProtoNumber(4) val bucketCount: Int,
    /** Non-empty buckets, sorted by index. */
    @ProtoNumber(5) val buckets: List<BucketRef>,
    @ProtoNumber(6) val categoriesRef: PayloadRef? = null,
    @ProtoNumber(7) val prefsRef: PayloadRef? = null,
    /** Every device that has committed, sorted by id. Never evicted automatically. */
    @ProtoNumber(8) val devices: List<DeviceRecord>,
)

/**
 * An immutable payload file. [name] and [storedSha] cover the stored bytes (gzip, then encryption), so ciphertext
 * under a new key never collides; change detection compares [plaintextSha], the sha of the encoded record bytes.
 */
@Serializable
data class PayloadRef(
    @ProtoNumber(1) val fileId: String,
    @ProtoNumber(2) val name: String,
    @ProtoNumber(3) val storedSha: String,
    @ProtoNumber(4) val plaintextSha: String,
    @ProtoNumber(5) val entryCount: Int,
)

@Serializable
data class BucketRef(
    @ProtoNumber(1) val index: Int,
    @ProtoNumber(2) val payload: PayloadRef,
)

/** Commit lineage per device; lets a device detect a rolled-back remote and resolve an ambiguous CAS. */
@Serializable
data class DeviceRecord(
    @ProtoNumber(1) val id: String,
    @ProtoNumber(2) val model: String,
    @ProtoNumber(3) val lastCommitId: String? = null,
    @ProtoNumber(4) val lastCommitSeq: Long,
    @ProtoNumber(5) val lastSyncAtMillis: Long,
)
