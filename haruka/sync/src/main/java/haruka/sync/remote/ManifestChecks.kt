package haruka.sync.remote

import haruka.sync.model.FORMAT_VERSION
import haruka.sync.model.ManifestBody
import haruka.sync.model.ManifestHeader

/** Why a sync stopped without writing anything. */
sealed interface SyncError {

    /** The remote was written by a newer Haruka; updating the app is the only way forward. */
    data class FormatTooNew(val formatVersion: Int, val supported: Int = FORMAT_VERSION) : SyncError

    /** The remote is encrypted with a key this device does not hold. */
    data class NeedsPassphrase(val keyId: String) : SyncError

    /**
     * The remote lost commits this device already applied or made (restored from an older copy, or another
     * install overwrote it). [foundSeq] is what the remote shows (null: this device is missing from `devices`),
     * [expectedSeq] the lowest value consistent with this device's history. Recovery is the user's choice
     * ("Upload this device's library": a union commit under a new epoch).
     */
    data class RemoteRolledBack(val foundSeq: Long?, val expectedSeq: Long) : SyncError

    /** The passphrase entered does not unlock the remote's key [keyId]. */
    data class WrongPassphrase(val keyId: String) : SyncError

    /**
     * Remote bytes are unreadable: a manifest or payload that does not decode, fails authentication (tampered,
     * truncated, moved) or does not match the sha the manifest records, or key params this build can't use.
     */
    data class Corrupt(val detail: String) : SyncError

    /**
     * This device encrypts but the remote is plaintext (encryption turned off elsewhere, or the remote replaced);
     * writing would publish the library unencrypted or re-encrypt the remote without anyone asking.
     */
    data object RemotePlaintext : SyncError
}

/** What this device remembers of the remote head its base was merged with. */
data class BaseLineage(
    val remoteEpoch: String,
    /** commitSeq of the head last applied. */
    val baseCommitSeq: Long,
    /** commitSeq of this device's last successful compare-and-swap in [remoteEpoch]; null if it never committed. */
    val lastOwnCommitSeq: Long?,
)

sealed interface HeaderCheck {
    /** Same epoch as the base: merge three-way; then run [checkOwnCommit] on the decrypted body. */
    data object ThreeWay : HeaderCheck

    /** No base, or the remote was reset or replaced (new epoch): union merge, never apply removals. */
    data object Union : HeaderCheck

    /** Stop before decrypting or writing anything. */
    data class Rejected(val error: SyncError) : HeaderCheck
}

/**
 * The plaintext header checks of the commit protocol, in order: format gate, key, epoch, rollback.
 * [localKeyId] is the id of the key this device holds, null without one.
 *
 * commitSeq is only comparable within an epoch, so a new epoch means union mode, never a rollback.
 */
fun checkHeader(header: ManifestHeader, base: BaseLineage?, localKeyId: String?): HeaderCheck {
    if (header.formatVersion > FORMAT_VERSION) {
        return HeaderCheck.Rejected(SyncError.FormatTooNew(header.formatVersion))
    }
    val keyId = header.encryption?.keyId
    if (keyId != null && keyId != localKeyId) return HeaderCheck.Rejected(SyncError.NeedsPassphrase(keyId))
    if (base == null || header.remoteEpoch != base.remoteEpoch) return HeaderCheck.Union
    if (header.commitSeq < base.baseCommitSeq) {
        return HeaderCheck.Rejected(SyncError.RemoteRolledBack(header.commitSeq, base.baseCommitSeq))
    }
    return HeaderCheck.ThreeWay
}

/**
 * The own-commit check, run on the decrypted body after [checkHeader] returned [HeaderCheck.ThreeWay]: the remote
 * must still list this device's last successful commit. It catches a remote whose header seq looks intact but
 * whose lineage no longer contains that commit.
 */
fun checkOwnCommit(body: ManifestBody, deviceId: String, base: BaseLineage): SyncError.RemoteRolledBack? {
    val expected = base.lastOwnCommitSeq ?: return null
    val found = body.deviceOf(deviceId)?.lastCommitSeq
    return if (found == null || found < expected) SyncError.RemoteRolledBack(found, expected) else null
}
