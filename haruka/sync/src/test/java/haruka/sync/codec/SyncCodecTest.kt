package haruka.sync.codec

import haruka.sync.model.Bucket
import haruka.sync.model.ChapterRecord
import haruka.sync.model.DedupOverrideRecord
import haruka.sync.model.EntryKey
import haruka.sync.model.EntryRecord
import haruka.sync.model.bucketOf
import haruka.sync.model.canonical
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

class SyncCodecTest {

    private val entry = EntryRecord(
        key = EntryKey(sourceId = 1, url = "/m/1"),
        favorite = true,
        favoriteAtMillis = 1000,
        categoryUuids = listOf("c1"),
        chapters = listOf(ChapterRecord(url = "/c/1", name = "Ch. 1", chapterNumber = 1.0, read = true)),
    )

    @Test
    fun `fixed bucket encodes to the golden bytes`() {
        // Hand-derived protobuf: only non-default fields, in declaration order, varint integers, fixed64 doubles.
        val golden = "080a" + "122f" + // Bucket.index = 10, Bucket.entries (47 bytes)
            "0a08" + "0801" + "12042f6d2f31" + // EntryRecord.key {sourceId = 1, url = "/m/1"}
            "1001" + // favorite = true
            "18e807" + // favoriteAtMillis = 1000
            "92010263" + "31" + // categoryUuids (field 18) = ["c1"]
            "aa0118" + // chapters (field 21), 24 bytes
            "0a042f632f31" + // url = "/c/1"
            "120543682e2031" + // name = "Ch. 1"
            "19000000000000f03f" + // chapterNumber = 1.0
            "4001" // read = true

        SyncCodec.encodeBucket(Bucket(index = 10, entries = listOf(entry))).toHexString() shouldBe golden
    }

    @Test
    fun `chapter with default state encodes only its index fields`() {
        val bucket =
            Bucket(index = 0, entries = listOf(EntryRecord(EntryKey(0, ""), chapters = listOf(ChapterRecord("u")))))

        // Fields without a default (index, key, url) are always written; every defaulted field is omitted.
        SyncCodec.encodeBucket(bucket).toHexString() shouldBe "0800" + "120c" + "0a0408001200" + "aa0103" + "0a0175"
    }

    @Test
    fun `bucket is the unsigned first four sha256 bytes mod 128`() {
        // From `printf '<sourceId>\0<url>' | shasum -a 256`: 71ea220a, e4f60d0a and 869a596e (high bit set).
        bucketOf(EntryKey(2499283573021220255, "/manga/one-piece")) shouldBe 10
        bucketOf(EntryKey(0, "")) shouldBe 10
        bucketOf(EntryKey(-1, "/title/12345")) shouldBe 110
    }

    @Test
    fun `index-only chapter additions do not change the digest`() {
        val withNewChapter = entry.copy(chapters = entry.chapters + ChapterRecord(url = "/c/2", name = "Ch. 2"))

        SyncCodec.entryDigest(withNewChapter) shouldBe SyncCodec.entryDigest(entry)
    }

    @Test
    fun `chapter state and entry user fields change the digest`() {
        val digest = SyncCodec.entryDigest(entry)

        SyncCodec.entryDigest(entry.copy(chapters = listOf(entry.chapters[0].copy(read = false)))) shouldNotBe digest
        SyncCodec.entryDigest(entry.copy(chapters = listOf(entry.chapters[0].copy(lastPageRead = 3)))) shouldNotBe
            digest
        SyncCodec.entryDigest(entry.copy(notes = "n")) shouldNotBe digest
        SyncCodec.entryDigest(entry.copy(categoryUuids = emptyList())) shouldNotBe digest
        SyncCodec.entryDigest(entry.copy(favorite = false)) shouldNotBe digest
    }

    @Test
    fun `history duration without a read time is normalized to zero`() {
        val chapter = ChapterRecord(url = "/c/1", historyReadAtMillis = null, historyDurationMs = 500)

        chapter.canonical() shouldBe chapter.copy(historyDurationMs = 0)
        chapter.canonical().hasDefaultState shouldBe true
        ChapterRecord(
            url = "/c/1",
            historyReadAtMillis = 1,
            historyDurationMs = 500,
        ).canonical().historyDurationMs shouldBe
            500
    }

    @Test
    fun `an override with no choices is the same as none`() {
        entry.copy(dedupOverride = DedupOverrideRecord()).canonical() shouldBe entry
    }

    @Test
    fun `canonical values are returned without copying`() {
        val bucket = Bucket(index = 10, entries = listOf(entry))

        (bucket.canonical() === bucket) shouldBe true
    }

    @Test
    fun `duplicate keys are rejected`() {
        shouldThrow<IllegalArgumentException> {
            SyncCodec.encodeBucket(Bucket(index = 0, entries = listOf(entry, entry)))
        }
        shouldThrow<IllegalArgumentException> {
            SyncCodec.encodeBucket(
                Bucket(
                    index = 0,
                    entries = listOf(
                        entry.copy(
                            chapters =
                            entry.chapters + entry.chapters,
                        ),
                    ),
                ),
            )
        }
    }
}
