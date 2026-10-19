package haruka.sync.crypto

import haruka.sync.codec.SyncArbs
import haruka.sync.codec.SyncCodec
import haruka.sync.model.BUCKET_COUNT
import haruka.sync.model.EncryptionParams
import haruka.sync.model.FORMAT_VERSION
import haruka.sync.model.ManifestHeader
import haruka.sync.remote.BaseLineage
import haruka.sync.remote.HeaderCheck
import haruka.sync.remote.Manifest
import haruka.sync.remote.ManifestFile
import haruka.sync.remote.RemoteLayout
import haruka.sync.remote.SyncError
import haruka.sync.remote.checkHeader
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.bind
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.random.Random

class SyncKeyTest {

    private val layout = RemoteLayout("app.haruka.dev")
    private val key = SyncKey.create(PASSPHRASE, TEST_ITERATIONS)

    private val manifests: Arb<Manifest> = Arb.bind(SyncArbs.manifestHeader, SyncArbs.manifestBody) { h, b ->
        Manifest(h.copy(encryption = key.params), b)
    }

    @Test
    fun `payloads round trip in every slot`() = forAll("payload round trip", payloads) { (slot, plaintext) ->
        val stored = key.encryptPayload(layout, slot, plaintext)

        stored.size shouldBe plaintext.size + OVERHEAD
        key.decryptPayload(layout, slot, stored) shouldBe plaintext
    }

    @Test
    fun `gzipped records round trip as the commit stores them`() = forAll("bucket round trip", SyncArbs.bucket) {
        val slot = PayloadSlot.Bucket(it.index)
        val stored = key.encryptPayload(layout, slot, SyncCodec.gzip(SyncCodec.encodeBucket(it)))

        SyncCodec.decodeBucket(SyncCodec.gunzip(key.decryptPayload(layout, slot, stored))) shouldBe it
    }

    @Test
    fun `manifests round trip through the stored file`() = forAll("manifest round trip", manifests) {
        val bytes = key.encryptManifest(layout, it).encode()
        val file = ManifestFile.decode(bytes)

        file.header shouldBe it.header
        key.decryptManifest(layout, file) shouldBe it
    }

    @Test
    fun `the same plaintext never encrypts to the same bytes`() {
        val plaintext = ByteArray(32) { it.toByte() }

        key.encryptPayload(layout, PayloadSlot.Prefs, plaintext) shouldNotBe
            key.encryptPayload(layout, PayloadSlot.Prefs, plaintext)
    }

    @Test
    fun `the right passphrase unlocks the params read back from the remote`() {
        val params = decodedParams(key)
        val unlocked = SyncKey.unlock(params, PASSPHRASE)!!
        val slot = PayloadSlot.Categories

        unlocked.keyId shouldBe key.keyId
        unlocked.params shouldBe key.params
        unlocked.decryptPayload(layout, slot, key.encryptPayload(layout, slot, byteArrayOf(1, 2, 3))) shouldBe
            byteArrayOf(1, 2, 3)
        key.decryptPayload(layout, slot, unlocked.encryptPayload(layout, slot, byteArrayOf(4))) shouldBe byteArrayOf(4)
    }

    @Test
    fun `a wrong passphrase fails the key check`() {
        SyncKey.unlock(key.params, "correct horse battery stapler") shouldBe null
        SyncKey.unlock(key.params, "") shouldBe null
    }

    @Test
    fun `altered params fail the key check`() {
        val params = key.params
        val flipped = params.keyCheck.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }

        SyncKey.unlock(params.copy(keyCheck = flipped), PASSPHRASE) shouldBe null
        SyncKey.unlock(params.copy(keyId = "other"), PASSPHRASE) shouldBe null
        SyncKey.unlock(params.copy(kdfSalt = ByteArray(16)), PASSPHRASE) shouldBe null
        SyncKey.unlock(params.copy(kdfIterations = TEST_ITERATIONS + 1), PASSPHRASE) shouldBe null
    }

    @Test
    fun `a device without the remote key stops with NeedsPassphrase`() {
        val header = ManifestHeader(FORMAT_VERSION, "e1", commitSeq = 4, encryption = key.params)
        // The same passphrase entered again makes a new key (new salt, new keyId), never the remote's.
        val recreated = SyncKey.create(PASSPHRASE, TEST_ITERATIONS)
        val base = BaseLineage("e1", baseCommitSeq = 4, lastOwnCommitSeq = null)
        val needs = HeaderCheck.Rejected(SyncError.NeedsPassphrase(key.keyId))

        checkHeader(header, base, localKeyId = null) shouldBe needs
        checkHeader(header, base, localKeyId = recreated.keyId) shouldBe needs
        checkHeader(header, base, localKeyId = SyncKey.unlock(header.encryption!!, PASSPHRASE)!!.keyId) shouldBe
            HeaderCheck.ThreeWay
        val stored = key.encryptPayload(layout, PayloadSlot.Prefs, byteArrayOf(9))
        shouldThrow<SyncCryptoException> { recreated.decryptPayload(layout, PayloadSlot.Prefs, stored) }
    }

    @Test
    fun `every flipped bit of a payload fails authentication`() {
        val stored = key.encryptPayload(layout, PayloadSlot.Bucket(7), ByteArray(24) { it.toByte() })

        for (bit in 0..<stored.size * 8) {
            val tampered = stored.copyOf()
            tampered[bit / 8] = (tampered[bit / 8].toInt() xor (1 shl (bit % 8))).toByte()
            shouldThrow<SyncCryptoException> { key.decryptPayload(layout, PayloadSlot.Bucket(7), tampered) }
        }
    }

    @Test
    fun `truncated or extended ciphertext fails authentication`() {
        val stored = key.encryptPayload(layout, PayloadSlot.Prefs, ByteArray(8))

        for (size in 0..<stored.size) {
            shouldThrow<SyncCryptoException> { key.decryptPayload(layout, PayloadSlot.Prefs, stored.copyOf(size)) }
        }
        shouldThrow<SyncCryptoException> { key.decryptPayload(layout, PayloadSlot.Prefs, stored + 0) }
    }

    @Test
    fun `a payload moved to another slot or namespace fails authentication`() {
        val stored = key.encryptPayload(layout, PayloadSlot.Bucket(5), byteArrayOf(1, 2, 3))
        val otherSlots =
            listOf(PayloadSlot.Bucket(6), PayloadSlot.Bucket(50), PayloadSlot.Categories, PayloadSlot.Prefs)

        for (slot in otherSlots) {
            shouldThrow<SyncCryptoException> { key.decryptPayload(layout, slot, stored) }
        }
        shouldThrow<SyncCryptoException> {
            key.decryptPayload(RemoteLayout("app.haruka"), PayloadSlot.Bucket(5), stored)
        }
        key.decryptPayload(layout, PayloadSlot.Bucket(5), stored) shouldBe byteArrayOf(1, 2, 3)
    }

    @Test
    fun `a manifest body under another header fails authentication`() {
        val manifest = Manifest.initial("d1", 42).let { it.copy(header = it.header.copy(encryption = key.params)) }
        val file = key.encryptManifest(layout, manifest)
        val swappedHeaders = listOf(
            file.header.copy(commitSeq = file.header.commitSeq + 1),
            file.header.copy(remoteEpoch = "other"),
            file.header.copy(formatVersion = FORMAT_VERSION + 1),
        )

        for (header in swappedHeaders) {
            shouldThrow<SyncCryptoException> { key.decryptManifest(layout, ManifestFile(header, file.storedBody)) }
        }
        shouldThrow<SyncCryptoException> { key.decryptManifest(RemoteLayout("app.haruka"), file) }
        shouldThrow<SyncCryptoException> { key.decryptPayload(layout, PayloadSlot.Prefs, file.storedBody) }
        val flipped = file.storedBody.copyOf().also { it[20] = (it[20].toInt() xor 0x10).toByte() }
        shouldThrow<SyncCryptoException> { key.decryptManifest(layout, ManifestFile(file.header, flipped)) }
    }

    @Test
    fun `a manifest must carry the key's params and name its key`() {
        val plain = Manifest.initial("d1", 42)
        val other = SyncKey.create(PASSPHRASE, TEST_ITERATIONS)

        shouldThrow<IllegalArgumentException> { key.encryptManifest(layout, plain) }
        shouldThrow<IllegalArgumentException> {
            key.encryptManifest(layout, plain.copy(header = plain.header.copy(encryption = other.params)))
        }
        val file = other.encryptManifest(layout, plain.copy(header = plain.header.copy(encryption = other.params)))
        shouldThrow<IllegalStateException> { key.decryptManifest(layout, file) }
    }

    @Test
    fun `params encode to the golden bytes and decode unchanged`() {
        val params = EncryptionParams(
            keyId = "k1",
            kdfAlgorithm = SyncKey.KDF_ALGORITHM,
            kdfIterations = SyncKey.KDF_ITERATIONS,
            kdfSalt = ByteArray(16) { it.toByte() },
            keyCheck = ByteArray(28) { (0x20 + it).toByte() },
        )
        val header = ManifestHeader(formatVersion = 1, remoteEpoch = "e1", commitSeq = 2, encryption = params)
        // Hand-derived protobuf: formatVersion, remoteEpoch, commitSeq, then encryption (78 bytes) with keyId,
        // kdfAlgorithm (20 bytes), kdfIterations (varint 310000), kdfSalt (16 bytes), keyCheck (28 bytes).
        val golden = "0801" + "12026531" + "1802" + "224e" +
            "0a026b31" + "1214" + "50424b44463257697468486d6163534841323536" + "18f0f512" +
            "2210" + "000102030405060708090a0b0c0d0e0f" +
            "2a1c" + "202122232425262728292a2b2c2d2e2f303132333435363738393a3b"

        val bytes = SyncCodec.encodeManifestHeader(header)

        bytes.toHexString() shouldBe golden
        SyncCodec.encodeManifestHeader(SyncCodec.decodeManifestHeader(bytes)).toHexString() shouldBe golden
        SyncCodec.decodeManifestHeader(bytes) shouldBe header
    }

    @Test
    fun `new keys use the plan's KDF params`() {
        val created = SyncKey.create(PASSPHRASE)

        created.params.kdfAlgorithm shouldBe "PBKDF2WithHmacSHA256"
        created.params.kdfIterations shouldBe 310_000
        created.params.kdfSalt.size shouldBe 16
        created.params.keyCheck.size shouldBe OVERHEAD
        created.keyId shouldNotBe key.keyId
        SyncKey.unlock(decodedParams(created), PASSPHRASE)!!.keyId shouldBe created.keyId
    }

    @Test
    fun `key derivation matches the RFC 7914 PBKDF2-HMAC-SHA256 vector`() {
        // RFC 7914 section 11: P = "passwd", S = "salt", c = 1; the first 32 bytes of the 64-byte output.
        SyncKey.deriveKeyBytes("passwd", "salt".encodeToByteArray(), 1).toHexString() shouldBe
            "55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc"
    }

    @Test
    fun `composed and decomposed passphrases derive the same key`() {
        val composed = SyncKey.create("caf\u00e9", TEST_ITERATIONS)

        SyncKey.unlock(composed.params, "cafe\u0301")!!.keyId shouldBe composed.keyId
    }

    @Test
    fun `unsupported or malformed params are rejected before deriving`() {
        val params = key.params

        shouldThrow<SyncCryptoException> { SyncKey.unlock(params.copy(kdfAlgorithm = "scrypt"), PASSPHRASE) }
        shouldThrow<SyncCryptoException> { SyncKey.unlock(params.copy(kdfIterations = 0), PASSPHRASE) }
        shouldThrow<SyncCryptoException> { SyncKey.unlock(params.copy(kdfIterations = Int.MAX_VALUE), PASSPHRASE) }
        shouldThrow<SyncCryptoException> { SyncKey.unlock(params.copy(kdfSalt = ByteArray(0)), PASSPHRASE) }
        shouldThrow<SyncCryptoException> { SyncKey.unlock(params.copy(keyCheck = ByteArray(27)), PASSPHRASE) }
        shouldThrow<IllegalArgumentException> { SyncKey.create("") }
    }

    @Test
    fun `the write gate refuses mismatched encryption states`() {
        val plaintext = ManifestHeader(FORMAT_VERSION, "e1", commitSeq = 1)
        val encrypted = plaintext.copy(encryption = key.params)
        val other = SyncKey.create(PASSPHRASE, TEST_ITERATIONS)

        checkEncryption(plaintext, key = null) shouldBe EncryptionCheck.Matches
        checkEncryption(encrypted, key) shouldBe EncryptionCheck.Matches
        checkEncryption(plaintext, key) shouldBe EncryptionCheck.RemotePlaintext
        checkEncryption(encrypted, key = null) shouldBe EncryptionCheck.NeedsPassphrase(key.keyId)
        checkEncryption(encrypted, other) shouldBe EncryptionCheck.NeedsPassphrase(key.keyId)
    }

    @Test
    fun `bucket slots stay in range and the key never prints its bytes`() {
        shouldThrow<IllegalArgumentException> { PayloadSlot.Bucket(BUCKET_COUNT) }
        shouldThrow<IllegalArgumentException> { PayloadSlot.Bucket(-1) }
        key.toString() shouldBe "SyncKey(keyId=${key.keyId})"
    }

    // The params as another device reads them from the manifest header.
    private fun decodedParams(key: SyncKey): EncryptionParams = SyncCodec.decodeManifestHeader(
        SyncCodec.encodeManifestHeader(ManifestHeader(FORMAT_VERSION, "e1", 1, encryption = key.params)),
    ).encryption!!

    private fun EncryptionParams.copy(
        keyId: String = this.keyId,
        kdfAlgorithm: String = this.kdfAlgorithm,
        kdfIterations: Int = this.kdfIterations,
        kdfSalt: ByteArray = this.kdfSalt,
        keyCheck: ByteArray = this.keyCheck,
    ) = EncryptionParams(keyId, kdfAlgorithm, kdfIterations, kdfSalt, keyCheck)

    private fun <T> forAll(name: String, arb: Arb<T>, property: suspend (T) -> Unit) {
        val seed = Random.nextLong()
        println("SyncKeyTest $name seed=$seed")
        runBlocking { checkAll(ITERATIONS, PropTestConfig(seed = seed), arb) { property(it) } }
    }

    private companion object {
        const val PASSPHRASE = "correct horse battery staple"

        // Derivation cost is irrelevant to these properties; one test covers the production count.
        const val TEST_ITERATIONS = 1_000
        const val ITERATIONS = 500
        const val OVERHEAD = 12 + 16

        val slots: List<PayloadSlot> =
            listOf(PayloadSlot.Categories, PayloadSlot.Prefs) + (0..<BUCKET_COUNT).map(PayloadSlot::Bucket)

        val payloads: Arb<Pair<PayloadSlot, ByteArray>> = arbitrary {
            slots.random(it.random) to it.random.nextBytes(it.random.nextInt(0, 2049))
        }
    }
}
