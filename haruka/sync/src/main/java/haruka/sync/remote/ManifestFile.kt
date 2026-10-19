package haruka.sync.remote

import haruka.sync.codec.SyncCodec
import haruka.sync.model.ManifestHeader
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * The bytes of the remote manifest file: the plaintext [header] and the body exactly as stored ([storedBody]:
 * protobuf, AES-GCM ciphertext of it when `header.encryption` is set).
 *
 * The file is a protobuf envelope `{1: header bytes, 2: body bytes}`. The envelope never changes, and protobuf
 * skips unknown fields, so a build can always read the header of a newer format and refuse with
 * [SyncError.FormatTooNew] before touching the body. The body is not gzipped: it is about 15 KB of short
 * strings, and the header must stay readable without any decoding step that a future format could change.
 */
class ManifestFile(val header: ManifestHeader, val storedBody: ByteArray) {

    fun encode(): ByteArray = ProtoBuf.encodeToByteArray(
        Envelope.serializer(),
        Envelope(SyncCodec.encodeManifestHeader(header), storedBody),
    )

    /** The manifest of a plaintext remote. Encrypted bodies are decrypted by the crypto layer instead. */
    fun decodePlaintext(): Manifest {
        check(header.encryption == null) { "Manifest body is encrypted with key ${header.encryption?.keyId}" }
        return Manifest(header, SyncCodec.decodeManifestBody(storedBody))
    }

    // Hand-written because data class equality compares ByteArray by reference.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        return other is ManifestFile && header == other.header && storedBody.contentEquals(other.storedBody)
    }

    override fun hashCode(): Int = 31 * header.hashCode() + storedBody.contentHashCode()

    override fun toString(): String = "ManifestFile(header=$header, storedBody=${storedBody.size} bytes)"

    companion object {
        /** Decodes the envelope and the header only; throws `SerializationException` on bytes that are not one. */
        fun decode(bytes: ByteArray): ManifestFile {
            val envelope = ProtoBuf.decodeFromByteArray(Envelope.serializer(), bytes)
            return ManifestFile(SyncCodec.decodeManifestHeader(envelope.header), envelope.body)
        }

        fun plaintext(manifest: Manifest): ManifestFile {
            require(manifest.header.encryption == null) { "Header declares encryption; store an encrypted body" }
            return ManifestFile(manifest.header, SyncCodec.encodeManifestBody(manifest.body))
        }
    }

    // Wire contract: field numbers never change, whatever FORMAT_VERSION becomes.
    @Serializable
    private class Envelope(
        @ProtoNumber(1) val header: ByteArray,
        @ProtoNumber(2) val body: ByteArray,
    )
}
