package haruka.sync.codec

import haruka.sync.model.Bucket
import haruka.sync.model.CategoriesPayload
import haruka.sync.model.ChapterRecord
import haruka.sync.model.DedupOverrideRecord
import haruka.sync.model.EntryKey
import haruka.sync.model.EntryRecord
import haruka.sync.model.ManifestBody
import haruka.sync.model.ManifestHeader
import haruka.sync.model.PrefsPayload
import haruka.sync.model.TrackRecord
import haruka.sync.model.canonical
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Encodes sync records to their plaintext bytes (protobuf of the canonical form, defaults omitted) and back.
 * Equal content always encodes to identical bytes, which is what `plaintextSha` change detection relies on.
 * Stored payloads are [gzip]ped plaintext (then encrypted when E2E is on); gzip output may differ between
 * zlib builds, so nothing compares gzip bytes across devices.
 */
object SyncCodec {

    private val protoBuf = ProtoBuf { encodeDefaults = false }

    fun encodeBucket(bucket: Bucket): ByteArray = protoBuf.encodeToByteArray(Bucket.serializer(), bucket.canonical())

    fun decodeBucket(bytes: ByteArray): Bucket = protoBuf.decodeFromByteArray(Bucket.serializer(), bytes)

    fun encodeCategories(payload: CategoriesPayload): ByteArray =
        protoBuf.encodeToByteArray(CategoriesPayload.serializer(), payload.canonical())

    fun decodeCategories(bytes: ByteArray): CategoriesPayload =
        protoBuf.decodeFromByteArray(CategoriesPayload.serializer(), bytes)

    fun encodePrefs(payload: PrefsPayload): ByteArray =
        protoBuf.encodeToByteArray(PrefsPayload.serializer(), payload.canonical())

    fun decodePrefs(bytes: ByteArray): PrefsPayload = protoBuf.decodeFromByteArray(PrefsPayload.serializer(), bytes)

    fun encodeManifestHeader(header: ManifestHeader): ByteArray =
        protoBuf.encodeToByteArray(ManifestHeader.serializer(), header)

    fun decodeManifestHeader(bytes: ByteArray): ManifestHeader =
        protoBuf.decodeFromByteArray(ManifestHeader.serializer(), bytes)

    fun encodeManifestBody(body: ManifestBody): ByteArray =
        protoBuf.encodeToByteArray(ManifestBody.serializer(), body.canonical())

    fun decodeManifestBody(bytes: ByteArray): ManifestBody =
        protoBuf.decodeFromByteArray(ManifestBody.serializer(), bytes)

    fun gzip(plaintext: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(plaintext.size / 4 + 64)
        GZIPOutputStream(out).use { it.write(plaintext) }
        return out.toByteArray()
    }

    fun gunzip(stored: ByteArray): ByteArray = GZIPInputStream(stored.inputStream()).use { it.readBytes() }

    fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHexString()

    /**
     * Hex SHA-256 over the entry's synced state: entry user fields, tracks and the state of chapters whose state
     * is not default. Source metadata, chapter metadata and memos are left out, and an index-only chapter
     * contributes nothing, so library updates that rename, reorder or add chapters never change the digest.
     */
    fun entryDigest(entry: EntryRecord): String {
        val canonical = entry.canonical()
        val state = EntryState(
            key = canonical.key,
            favorite = canonical.favorite,
            favoriteAtMillis = canonical.favoriteAtMillis,
            removedAtMillis = canonical.removedAtMillis,
            viewerFlags = canonical.viewerFlags,
            chapterFlags = canonical.chapterFlags,
            notes = canonical.notes,
            categoryUuids = canonical.categoryUuids,
            excludedScanlators = canonical.excludedScanlators,
            dedupOverride = canonical.dedupOverride,
            chapters = canonical.chapters.mapNotNull { it.takeUnless(ChapterRecord::hasDefaultState)?.toState() },
            tracks = canonical.tracks,
        )
        return sha256Hex(protoBuf.encodeToByteArray(EntryState.serializer(), state))
    }

    private fun ChapterRecord.toState() = ChapterState(
        url = url,
        read = read,
        bookmark = bookmark,
        lastPageRead = lastPageRead,
        historyReadAtMillis = historyReadAtMillis,
        historyDurationMs = historyDurationMs,
    )

    // Digest-only projections; never stored, so their field numbers carry no compatibility promise.
    @Serializable
    private class EntryState(
        @ProtoNumber(1) val key: EntryKey,
        @ProtoNumber(2) val favorite: Boolean = false,
        @ProtoNumber(3) val favoriteAtMillis: Long? = null,
        @ProtoNumber(4) val removedAtMillis: Long? = null,
        @ProtoNumber(5) val viewerFlags: Long = 0,
        @ProtoNumber(6) val chapterFlags: Long = 0,
        @ProtoNumber(7) val notes: String = "",
        @ProtoNumber(8) val categoryUuids: List<String> = emptyList(),
        @ProtoNumber(9) val excludedScanlators: List<String> = emptyList(),
        @ProtoNumber(10) val dedupOverride: DedupOverrideRecord? = null,
        @ProtoNumber(11) val chapters: List<ChapterState> = emptyList(),
        @ProtoNumber(12) val tracks: List<TrackRecord> = emptyList(),
    )

    @Serializable
    private class ChapterState(
        @ProtoNumber(1) val url: String,
        @ProtoNumber(2) val read: Boolean = false,
        @ProtoNumber(3) val bookmark: Boolean = false,
        @ProtoNumber(4) val lastPageRead: Long = 0,
        @ProtoNumber(5) val historyReadAtMillis: Long? = null,
        @ProtoNumber(6) val historyDurationMs: Long = 0,
    )
}
