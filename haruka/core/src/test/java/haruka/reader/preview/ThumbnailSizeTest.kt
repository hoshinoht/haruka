package haruka.reader.preview

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ThumbnailSizeTest {

    @Test
    fun `a regular page shrinks by the largest power of two that stays at or above the target`() {
        thumbnailSampleSize(width = 1200, height = 1800, targetWidth = 160) shouldBe 4 // 300 px wide
        thumbnailSampleSize(width = 1280, height = 1800, targetWidth = 160) shouldBe 8 // exactly 160 px
        thumbnailSampleSize(width = 1279, height = 1800, targetWidth = 160) shouldBe 4 // 8 would give 159 px
        thumbnailSampleSize(width = 4000, height = 6000, targetWidth = 160) shouldBe 16 // 250 px wide
    }

    @Test
    fun `a page at or below the target is never upscaled`() {
        thumbnailSampleSize(width = 160, height = 240, targetWidth = 160) shouldBe 1
        thumbnailSampleSize(width = 319, height = 480, targetWidth = 160) shouldBe 1
        thumbnailSampleSize(width = 100, height = 150, targetWidth = 160) shouldBe 1
    }

    @Test
    fun `unknown sizes decode at full size`() {
        thumbnailSampleSize(width = 0, height = 1000, targetWidth = 160) shouldBe 1
        thumbnailSampleSize(width = -1, height = -1, targetWidth = 160) shouldBe 1
        thumbnailSampleSize(width = 1000, height = 0, targetWidth = 160) shouldBe 1
        thumbnailSampleSize(width = 1000, height = 1000, targetWidth = 0) shouldBe 1
    }

    @Test
    fun `a long strip page samples by its width alone`() {
        thumbnailSampleSize(width = 720, height = 20_000, targetWidth = 160) shouldBe 4
        thumbnailSampleSize(width = 800, height = 60_000, targetWidth = 160) shouldBe 4
    }

    @Test
    fun `a wide spread samples by its width too`() {
        thumbnailSampleSize(width = 2400, height = 1700, targetWidth = 160) shouldBe 8
    }

    @Test
    fun `a long strip page keeps only its top three widths`() {
        thumbnailCropHeight(width = 720, height = 20_000) shouldBe 2160
        thumbnailCropHeight(width = 800, height = 60_000) shouldBe 2400
        thumbnailCropHeight(width = 720, height = 20_000, maxAspect = 2) shouldBe 1440
    }

    @Test
    fun `a page within the aspect cap keeps its full height`() {
        thumbnailCropHeight(width = 1200, height = 1800) shouldBe 1800
        thumbnailCropHeight(width = 720, height = 2160) shouldBe 2160 // exactly 3x
        thumbnailCropHeight(width = 2400, height = 1700) shouldBe 1700
    }

    @Test
    fun `a crop height never overflows on huge widths`() {
        thumbnailCropHeight(width = Int.MAX_VALUE, height = Int.MAX_VALUE) shouldBe Int.MAX_VALUE
    }

    @Test
    fun `unknown sizes are not cropped`() {
        thumbnailCropHeight(width = 0, height = 500) shouldBe 500
        thumbnailCropHeight(width = 500, height = -1) shouldBe 0
    }
}
