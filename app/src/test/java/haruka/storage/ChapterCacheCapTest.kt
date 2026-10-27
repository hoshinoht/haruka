package haruka.storage

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ChapterCacheCapTest {

    @Test
    fun `trims after enough writes`() {
        ChapterCacheCap.shouldTrim(ChapterCacheCap.TRIM_EVERY_WRITES - 1, 0) shouldBe false
        ChapterCacheCap.shouldTrim(ChapterCacheCap.TRIM_EVERY_WRITES, 0) shouldBe true
    }

    @Test
    fun `trims once enough time has passed`() {
        ChapterCacheCap.shouldTrim(1, ChapterCacheCap.TRIM_INTERVAL_MS - 1) shouldBe false
        ChapterCacheCap.shouldTrim(1, ChapterCacheCap.TRIM_INTERVAL_MS) shouldBe true
        // The first write ever, with no trim before it
        ChapterCacheCap.shouldTrim(1, Long.MAX_VALUE) shouldBe true
    }

    @Test
    fun `limits are SI megabytes`() {
        ChapterCacheCap.limitBytes(0) shouldBe 0L
        ChapterCacheCap.limitBytes(250) shouldBe 250_000_000L
        ChapterCacheCap.limitBytes(2000) shouldBe 2_000_000_000L
    }
}
