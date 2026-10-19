package haruka.sync.crypto

import haruka.sync.model.ManifestHeader

/** Whether this device's encryption state lets it commit on top of a remote head. */
sealed interface EncryptionCheck {
    /** Both plaintext, or both under the same key. */
    data object Matches : EncryptionCheck

    /** The remote is encrypted with a key this device does not hold: ask for the passphrase, write nothing. */
    data class NeedsPassphrase(val keyId: String) : EncryptionCheck

    /**
     * This device encrypts but the remote is plaintext: encryption was turned off elsewhere, or the remote was
     * replaced. Writing would either publish this device's library in plaintext or re-encrypt the remote without
     * anyone asking, so nothing is written until the user decides.
     */
    data object RemotePlaintext : EncryptionCheck
}

/**
 * The encryption gate of a regular commit, with [key] the key this device holds (null: it syncs in plaintext).
 * Enabling, disabling or changing the passphrase are explicit rewrite commits and do not go through this gate.
 */
fun checkEncryption(header: ManifestHeader, key: SyncKey?): EncryptionCheck {
    val remoteKeyId = header.encryption?.keyId
    return when {
        remoteKeyId == null && key == null -> EncryptionCheck.Matches
        remoteKeyId == null -> EncryptionCheck.RemotePlaintext
        remoteKeyId != key?.keyId -> EncryptionCheck.NeedsPassphrase(remoteKeyId)
        else -> EncryptionCheck.Matches
    }
}
