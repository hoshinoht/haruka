package haruka.sync.codec

import haruka.sync.model.Bucket
import haruka.sync.model.CategoriesPayload
import haruka.sync.model.EntryRecord
import haruka.sync.model.PrefsPayload
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.random.Random

class SyncCodecPropertyTest {

    /** Runs [property] on 1,000 samples with a fresh seed, printed so a failure can be replayed. */
    private fun <T> forAll(name: String, arb: Arb<T>, property: suspend (T) -> Unit) {
        val seed = Random.nextLong()
        println("SyncCodecPropertyTest $name seed=$seed")
        runBlocking { checkAll(ITERATIONS, PropTestConfig(seed = seed), arb) { property(it) } }
    }

    @Test
    fun `bucket round trips`() = forAll("bucket round trip", SyncArbs.bucket) {
        SyncCodec.decodeBucket(SyncCodec.encodeBucket(it)) shouldBe it
    }

    @Test
    fun `categories round trip`() = forAll("categories round trip", SyncArbs.categories) {
        SyncCodec.decodeCategories(SyncCodec.encodeCategories(it)) shouldBe it
    }

    @Test
    fun `prefs round trip`() = forAll("prefs round trip", SyncArbs.prefs) {
        SyncCodec.decodePrefs(SyncCodec.encodePrefs(it)) shouldBe it
    }

    @Test
    fun `manifest header round trips`() = forAll("header round trip", SyncArbs.manifestHeader) {
        SyncCodec.decodeManifestHeader(SyncCodec.encodeManifestHeader(it)) shouldBe it
    }

    @Test
    fun `manifest body round trips`() = forAll("body round trip", SyncArbs.manifestBody) {
        SyncCodec.decodeManifestBody(SyncCodec.encodeManifestBody(it)) shouldBe it
    }

    @Test
    fun `gzip round trips the encoded bucket`() = forAll("gzip round trip", SyncArbs.bucket) {
        val plaintext = SyncCodec.encodeBucket(it)
        SyncCodec.gunzip(SyncCodec.gzip(plaintext)) shouldBe plaintext
    }

    @Test
    fun `bucket bytes do not depend on input order`() = forAll("bucket permuted", SyncArbs.bucket) { bucket ->
        val random = Random(bucket.hashCode())
        val permuted = Bucket(bucket.index, bucket.entries.map { it.scrambled(random) }.shuffled(random))
        SyncCodec.encodeBucket(permuted) shouldBe SyncCodec.encodeBucket(bucket)
        SyncCodec.entryDigestsOf(permuted) shouldBe SyncCodec.entryDigestsOf(bucket)
    }

    @Test
    fun `categories bytes do not depend on input order`() = forAll("categories permuted", SyncArbs.categories) {
        val permuted = CategoriesPayload(it.categories.shuffled(Random(it.hashCode())))
        SyncCodec.encodeCategories(permuted) shouldBe SyncCodec.encodeCategories(it)
    }

    @Test
    fun `prefs bytes do not depend on input order`() = forAll("prefs permuted", SyncArbs.prefs) {
        val permuted = PrefsPayload(it.prefs.shuffled(Random(it.hashCode())))
        SyncCodec.encodePrefs(permuted) shouldBe SyncCodec.encodePrefs(it)
    }

    @Test
    fun `manifest body bytes do not depend on input order`() = forAll("body permuted", SyncArbs.manifestBody) {
        val random = Random(it.hashCode())
        val permuted = it.copy(buckets = it.buckets.shuffled(random), devices = it.devices.shuffled(random))
        SyncCodec.encodeManifestBody(permuted) shouldBe SyncCodec.encodeManifestBody(it)
    }

    @Test
    fun `entry digest ignores chapter metadata and memos`() = forAll("digest metadata", SyncArbs.bucket) { bucket ->
        bucket.entries.forEach { entry ->
            val churned = entry.copy(
                title = entry.title + "!",
                memoJson = "{\"changed\":true}",
                chapters = entry.chapters.mapIndexed { i, chapter ->
                    chapter.copy(
                        name = chapter.name + "!",
                        chapterNumber = chapter.chapterNumber + 1,
                        scanlator = "other",
                        sourceOrder = chapter.sourceOrder + i,
                        dateUploadMillis = 1L,
                        memoJson = "{\"changed\":true}",
                    )
                },
            )
            SyncCodec.entryDigest(churned) shouldBe SyncCodec.entryDigest(entry)
        }
    }

    /** Same content as this entry in a non-canonical shape: lists shuffled, set duplicates, unnormalized duration. */
    private fun EntryRecord.scrambled(random: Random) = copy(
        categoryUuids = (categoryUuids + categoryUuids.take(1)).shuffled(random),
        excludedScanlators = excludedScanlators.shuffled(random),
        chapters = chapters.map {
            if (it.historyReadAtMillis == null) it.copy(historyDurationMs = random.nextLong(1, 1_000)) else it
        }.shuffled(random),
        tracks = tracks.shuffled(random),
    )

    private fun SyncCodec.entryDigestsOf(bucket: Bucket) = bucket.entries.sortedBy { it.key }.map(::entryDigest)

    private companion object {
        const val ITERATIONS = 1_000
    }
}
