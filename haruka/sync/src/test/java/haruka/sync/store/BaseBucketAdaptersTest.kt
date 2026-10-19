package haruka.sync.store

import haruka.sync.model.ChapterRecord
import haruka.sync.model.EntryKey
import haruka.sync.model.EntryRecord
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class BaseBucketAdaptersTest {

    private fun entry(id: Long, vararg urls: String) = EntryRecord(
        key = EntryKey(sourceId = 1, url = "/m/$id"),
        favorite = true,
        title = "Entry $id",
        chapters = urls.map { ChapterRecord(url = it, name = it, chapterNumber = 1.0) },
    )

    @Test
    fun `presence survives store to merge to store`() {
        val stored = BaseBucket(
            index = 3,
            entries = listOf(
                BaseEntry(entry(1, "/c/1", "/c/2", "/c/3"), absentChapterUrls = listOf("/c/2")),
                // Row missing locally: every chapter absent.
                BaseEntry(entry(2, "/c/9"), absentChapterUrls = listOf("/c/9")),
                BaseEntry(entry(3), absentChapterUrls = emptyList()),
            ),
        ).canonical()

        val merge = stored.toMergeBase()
        merge.bucket.entries.map { it.key.url } shouldBe listOf("/m/1", "/m/2", "/m/3")
        merge.localPresent shouldBe mapOf(EntryKey(1, "/m/1") to setOf("/c/1", "/c/3"))

        baseBucketOf(merge.bucket, merge.localPresent) shouldBe stored
    }

    @Test
    fun `chapters outside the merge presence map are stored as absent`() {
        val merged = haruka.sync.model.Bucket(index = 0, entries = listOf(entry(1, "/c/1", "/c/2")))
        val base = baseBucketOf(merged, mapOf(EntryKey(1, "/m/1") to setOf("/c/1")))
        base.entries.single().absentChapterUrls shouldBe listOf("/c/2")
        base.entries.single().isLocalPresent("/c/1") shouldBe true
    }
}
