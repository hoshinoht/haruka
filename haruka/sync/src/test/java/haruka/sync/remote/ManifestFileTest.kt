package haruka.sync.remote

import haruka.sync.codec.SyncArbs
import haruka.sync.model.BUCKET_COUNT
import haruka.sync.model.BucketRef
import haruka.sync.model.DeviceRecord
import haruka.sync.model.EncryptionParams
import haruka.sync.model.FORMAT_VERSION
import haruka.sync.model.ManifestBody
import haruka.sync.model.ManifestHeader
import haruka.sync.model.PayloadRef
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.bind
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import org.junit.jupiter.api.Test
import kotlin.random.Random

class ManifestFileTest {

    private val manifest = Manifest(
        header = ManifestHeader(formatVersion = 1, remoteEpoch = "e1", commitSeq = 2),
        body = ManifestBody(
            commitId = "c1",
            writerDeviceId = "d1",
            writtenAtMillis = 3,
            bucketCount = 128,
            buckets = listOf(BucketRef(5, PayloadRef("f1", "n1", "s1", "p1", entryCount = 7))),
            devices = listOf(DeviceRecord("d1", "m1", lastCommitId = "c1", lastCommitSeq = 2, lastSyncAtMillis = 3)),
        ),
    )

    @Test
    fun `fixed plaintext manifest encodes to the golden bytes`() {
        // Hand-derived protobuf: envelope {1: header bytes, 2: body bytes}; varint integers, fields in order.
        val header = "0801" + "12026531" + "1802" // formatVersion = 1, remoteEpoch = "e1", commitSeq = 2
        val body = "0a026331" + "12026431" + "1803" + "208001" + // commitId, writerDeviceId, writtenAt, bucketCount
            "2a16" + "0805" + "1212" + // buckets[0] (22 bytes): index = 5, payload (18 bytes)
            "0a026631" + "12026e31" + "1a027331" + "22027031" + "2807" + // fileId, name, storedSha, plaintextSha, 7
            "4210" + // devices[0] (16 bytes)
            "0a026431" + "12026d31" + "1a026331" + "2002" + "2803" // id, model, lastCommitId, seq 2, syncAt 3
        val golden = "0a08" + header + "1237" + body

        val bytes = ManifestFile.plaintext(manifest).encode()

        bytes.toHexString() shouldBe golden
        ManifestFile.decode(bytes).decodePlaintext() shouldBe manifest
    }

    @Test
    fun `plaintext manifests round trip`() = forAll("plaintext round trip", plaintextManifests) {
        ManifestFile.decode(ManifestFile.plaintext(it).encode()).decodePlaintext() shouldBe it
    }

    @Test
    fun `stored bodies round trip byte for byte`() = forAll("stored body round trip", storedFiles) {
        ManifestFile.decode(it.encode()) shouldBe it
    }

    @Test
    fun `an encrypted body is never decoded as plaintext`() {
        val encrypted = ManifestFile(manifest.header.copy(encryption = encryption), byteArrayOf(1, 2, 3))

        shouldThrow<IllegalStateException> { ManifestFile.decode(encrypted.encode()).decodePlaintext() }
        shouldThrow<IllegalArgumentException> { ManifestFile.plaintext(manifest.copy(header = encrypted.header)) }
    }

    @Test
    fun `the header of a newer format is readable and gated before the body`() {
        // A future writer adds header field 9 and changes the body to something this build cannot decode.
        val futureHeader = ProtoBuf.encodeToByteArray(
            FutureHeader.serializer(),
            FutureHeader(formatVersion = FORMAT_VERSION + 1, remoteEpoch = "e1", commitSeq = 9, successor = "x"),
        )
        val bytes = ProtoBuf.encodeToByteArray(
            TestEnvelope.serializer(),
            TestEnvelope(futureHeader, byteArrayOf(-1, -1, -1)),
        )

        val file = ManifestFile.decode(bytes)

        file.header shouldBe ManifestHeader(formatVersion = FORMAT_VERSION + 1, remoteEpoch = "e1", commitSeq = 9)
        checkHeader(file.header, BaseLineage("e1", 2, null), localKeyId = null) shouldBe
            HeaderCheck.Rejected(SyncError.FormatTooNew(FORMAT_VERSION + 1, FORMAT_VERSION))
    }

    @Test
    fun `bytes that are not a manifest are rejected`() {
        shouldThrow<Exception> { ManifestFile.decode(byteArrayOf(0x0a, 0x7f, 0x01)) }
    }

    @Test
    fun `the initial manifest starts a fresh epoch at seq 0 with nothing in it`() {
        val first = Manifest.initial(writerDeviceId = "d1", nowMillis = 42)
        val second = Manifest.initial(writerDeviceId = "d1", nowMillis = 42)

        first.header shouldBe ManifestHeader(FORMAT_VERSION, first.header.remoteEpoch, commitSeq = 0)
        first.body shouldBe
            ManifestBody(first.body.commitId, "d1", 42, BUCKET_COUNT, emptyList(), devices = emptyList())
        first.header.remoteEpoch shouldNotBe second.header.remoteEpoch
        first.body.commitId shouldNotBe second.body.commitId
        Manifest.initial("d1", 42, remoteEpoch = "fixed").header.remoteEpoch shouldBe "fixed"
        ManifestFile.decode(ManifestFile.plaintext(first).encode()).decodePlaintext() shouldBe first
    }

    @Test
    fun `devices are looked up by id`() {
        manifest.body.deviceOf("d1") shouldBe manifest.body.devices.single()
        manifest.body.deviceOf("d2") shouldBe null
    }

    private fun <T> forAll(name: String, arb: Arb<T>, property: suspend (T) -> Unit) {
        val seed = Random.nextLong()
        println("ManifestFileTest $name seed=$seed")
        runBlocking { checkAll(ITERATIONS, PropTestConfig(seed = seed), arb) { property(it) } }
    }

    @Serializable
    private class FutureHeader(
        @ProtoNumber(1) val formatVersion: Int,
        @ProtoNumber(2) val remoteEpoch: String,
        @ProtoNumber(3) val commitSeq: Long,
        @ProtoNumber(9) val successor: String,
    )

    @Serializable
    private class TestEnvelope(
        @ProtoNumber(1) val header: ByteArray,
        @ProtoNumber(2) val body: ByteArray,
    )

    private companion object {
        const val ITERATIONS = 1_000

        val encryption = EncryptionParams("k1", "PBKDF2WithHmacSHA256", 600_000, byteArrayOf(1), byteArrayOf(2))

        val plaintextManifests: Arb<Manifest> = Arb.bind(SyncArbs.manifestHeader, SyncArbs.manifestBody) { h, b ->
            Manifest(h.copy(encryption = null), b)
        }

        // Any header, with a body of arbitrary bytes standing in for ciphertext.
        val storedFiles: Arb<ManifestFile> = Arb.bind(
            SyncArbs.manifestHeader,
            arbitrary { it.random.nextBytes(it.random.nextInt(0, 65)) },
        ) { header, body -> ManifestFile(header, body) }
    }
}
