package haruka.sync.crypto

import haruka.sync.codec.SyncCodec
import haruka.sync.model.BUCKET_COUNT
import haruka.sync.model.EncryptionParams
import haruka.sync.model.ManifestHeader
import haruka.sync.remote.Manifest
import haruka.sync.remote.ManifestFile
import haruka.sync.remote.RemoteKind
import haruka.sync.remote.RemoteLayout
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.SecureRandom
import java.text.Normalizer
import java.util.UUID
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * The end-to-end encryption key of one encrypted remote. It lives in memory only; this module never persists it.
 *
 * Every encrypted file is `nonce (12 bytes) || AES-256-GCM ciphertext || 128-bit tag`, with a fresh random nonce
 * per file. The associated data binds each ciphertext to its place in the remote: the namespace plus the payload
 * slot, or the manifest header (format, epoch, commitSeq, keyId). A valid ciphertext moved to another slot,
 * namespace or commit fails authentication exactly like a flipped bit.
 */
class SyncKey private constructor(
    /** Goes into `ManifestHeader.encryption` of every manifest this key encrypts. */
    val params: EncryptionParams,
    private val key: SecretKey,
) {
    val keyId: String get() = params.keyId

    /** Encrypts the stored form of a payload (gzipped plaintext) for [slot] of [layout]. */
    fun encryptPayload(layout: RemoteLayout, slot: PayloadSlot, plaintext: ByteArray): ByteArray =
        seal(key, payloadAad(layout, slot), plaintext)

    /** @throws SyncCryptoException when [stored] was not written by this key for exactly this slot of [layout]. */
    fun decryptPayload(layout: RemoteLayout, slot: PayloadSlot, stored: ByteArray): ByteArray =
        open(key, payloadAad(layout, slot), stored)

    /** The manifest file with [manifest]'s body encrypted; its header must already carry [params]. */
    fun encryptManifest(layout: RemoteLayout, manifest: Manifest): ManifestFile {
        require(manifest.header.encryption == params) { "Manifest header does not carry the params of key $keyId" }
        val body = SyncCodec.encodeManifestBody(manifest.body)
        return ManifestFile(manifest.header, seal(key, manifestAad(layout, manifest.header), body))
    }

    /**
     * Decrypts the body of [file]. Run `checkHeader` first: a header naming another key is a caller error.
     * @throws SyncCryptoException when the body was not written under this exact header by this key.
     */
    fun decryptManifest(layout: RemoteLayout, file: ManifestFile): Manifest {
        check(file.header.encryption?.keyId == keyId) { "Manifest is not encrypted with key $keyId" }
        val body = open(key, manifestAad(layout, file.header), file.storedBody)
        return Manifest(file.header, SyncCodec.decodeManifestBody(body))
    }

    override fun toString(): String = "SyncKey(keyId=$keyId)"

    companion object {
        /** JCA name of the KDF; available on the JVM and on Android API 26+. */
        const val KDF_ALGORITHM = "PBKDF2WithHmacSHA256"

        /** OWASP's PBKDF2-HMAC-SHA256 recommendation; used for every new key. */
        const val KDF_ITERATIONS = 310_000

        // Upper bound for params read from the remote, so a corrupt header can't stall a sync for hours.
        private const val MAX_KDF_ITERATIONS = 10_000_000
        private const val SALT_BYTES = 16
        private const val KEY_BITS = 256
        private const val NONCE_BYTES = 12
        private const val TAG_BITS = 128
        private const val TAG_BYTES = TAG_BITS / 8
        private const val TRANSFORMATION = "AES/GCM/NoPadding"

        // Versions the associated-data layout; never reuse it for a different layout.
        private const val AAD_DOMAIN = "haruka.sync.crypto/1"

        private val random = SecureRandom()

        /**
         * A new key from [passphrase] with a fresh salt and keyId. Enabling encryption or changing the passphrase
         * uses a new key, so the rewrite commit gets a new keyId and other devices stop with NeedsPassphrase.
         */
        fun create(passphrase: String): SyncKey = create(passphrase, KDF_ITERATIONS)

        // Tests pass fewer iterations; production always uses KDF_ITERATIONS.
        internal fun create(passphrase: String, iterations: Int): SyncKey {
            require(passphrase.isNotEmpty()) { "Empty passphrase" }
            val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
            val keyId = UUID.randomUUID().toString()
            val key = deriveKey(passphrase, salt, iterations)
            val keyCheck = seal(key, keyCheckAad(keyId), ByteArray(0))
            return SyncKey(EncryptionParams(keyId, KDF_ALGORITHM, iterations, salt, keyCheck), key)
        }

        /**
         * The key [params] describe if [passphrase] is the one it was created from; null when the key check fails
         * (wrong passphrase, or params altered after creation).
         * @throws SyncCryptoException when [params] name a KDF this build does not support or are malformed.
         */
        fun unlock(params: EncryptionParams, passphrase: String): SyncKey? {
            if (params.kdfAlgorithm != KDF_ALGORITHM) {
                throw SyncCryptoException("Unsupported KDF ${params.kdfAlgorithm}")
            }
            if (params.kdfIterations !in 1..MAX_KDF_ITERATIONS) {
                throw SyncCryptoException("KDF iterations out of range: ${params.kdfIterations}")
            }
            if (params.kdfSalt.isEmpty()) throw SyncCryptoException("Empty KDF salt")
            if (params.keyCheck.size != NONCE_BYTES + TAG_BYTES) {
                throw SyncCryptoException("Key check has ${params.keyCheck.size} bytes")
            }
            if (passphrase.isEmpty()) return null
            val key = deriveKey(passphrase, params.kdfSalt, params.kdfIterations)
            return try {
                open(key, keyCheckAad(params.keyId), params.keyCheck)
                SyncKey(params, key)
            } catch (e: SyncCryptoException) {
                null
            }
        }

        /**
         * PBKDF2 over the NFC form of [passphrase], so composed and decomposed input of the same text (different
         * keyboards or platforms) derive the same key.
         */
        internal fun deriveKeyBytes(passphrase: String, salt: ByteArray, iterations: Int): ByteArray {
            val chars = Normalizer.normalize(passphrase, Normalizer.Form.NFC).toCharArray()
            val spec = PBEKeySpec(chars, salt, iterations, KEY_BITS)
            try {
                return SecretKeyFactory.getInstance(KDF_ALGORITHM).generateSecret(spec).encoded
            } finally {
                spec.clearPassword()
                chars.fill('\u0000')
            }
        }

        private fun deriveKey(passphrase: String, salt: ByteArray, iterations: Int): SecretKey {
            val bytes = deriveKeyBytes(passphrase, salt, iterations)
            try {
                return SecretKeySpec(bytes, "AES")
            } finally {
                bytes.fill(0)
            }
        }

        private fun seal(key: SecretKey, aad: ByteArray, plaintext: ByteArray): ByteArray {
            val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(aad)
            val out = ByteArray(NONCE_BYTES + cipher.getOutputSize(plaintext.size))
            nonce.copyInto(out)
            cipher.doFinal(plaintext, 0, plaintext.size, out, NONCE_BYTES)
            return out
        }

        private fun open(key: SecretKey, aad: ByteArray, stored: ByteArray): ByteArray {
            if (stored.size < NONCE_BYTES + TAG_BYTES) {
                throw SyncCryptoException("Ciphertext too short: ${stored.size} bytes")
            }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, stored, 0, NONCE_BYTES))
            cipher.updateAAD(aad)
            try {
                return cipher.doFinal(stored, NONCE_BYTES, stored.size - NONCE_BYTES)
            } catch (e: AEADBadTagException) {
                throw SyncCryptoException("Ciphertext failed authentication", e)
            }
        }

        private fun keyCheckAad(keyId: String) = aad("key-check", keyId)

        private fun payloadAad(layout: RemoteLayout, slot: PayloadSlot) = aad(
            layout.namespace,
            slot.kind.wire,
            if (slot is PayloadSlot.Bucket) slot.index.toString() else "",
        )

        private fun manifestAad(layout: RemoteLayout, header: ManifestHeader) = aad(
            layout.namespace,
            RemoteKind.MANIFEST.wire,
            header.formatVersion.toString(),
            header.remoteEpoch,
            header.commitSeq.toString(),
            requireNotNull(header.encryption).keyId,
        )

        // Length-prefixed UTF-8 parts, so no two different part lists encode to the same bytes.
        private fun aad(vararg parts: String): ByteArray {
            val out = ByteArrayOutputStream()
            DataOutputStream(out).use { data ->
                for (part in arrayOf(AAD_DOMAIN, *parts)) {
                    val bytes = part.encodeToByteArray()
                    data.writeInt(bytes.size)
                    data.write(bytes)
                }
            }
            return out.toByteArray()
        }
    }
}

/**
 * The logical place of a payload in the remote. Payload names carry the sha of the ciphertext, so the associated
 * data binds this slot instead of the name.
 */
sealed interface PayloadSlot {
    val kind: RemoteKind

    data class Bucket(val index: Int) : PayloadSlot {
        override val kind get() = RemoteKind.BUCKET

        init {
            require(index in 0..<BUCKET_COUNT) { "Bucket index out of range: $index" }
        }
    }

    data object Categories : PayloadSlot {
        override val kind get() = RemoteKind.CATEGORIES
    }

    data object Prefs : PayloadSlot {
        override val kind get() = RemoteKind.PREFS
    }
}

/** Stored bytes that fail authentication (tampered, truncated, moved to another slot) or unusable key params. */
class SyncCryptoException(message: String, cause: Throwable? = null) : Exception(message, cause)
