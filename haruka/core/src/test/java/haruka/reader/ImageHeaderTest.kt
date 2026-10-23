package haruka.reader

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class ImageHeaderTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun ascii(text: String) = text.toByteArray(Charsets.US_ASCII)

    private fun size(data: ByteArray) = ImageHeader.readSize(ByteArrayInputStream(data))

    // Bytes after the header stand in for image data the reader must not need
    private val imageData = ByteArray(4096) { 0x55 }

    @Test
    fun png() {
        val png = bytes(0x89) + ascii("PNG") + bytes(0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13) + ascii("IHDR") +
            bytes(0, 0, 0x02, 0xBC, 0, 0, 0x2E, 0xE0) + imageData
        size(png) shouldBe PageSize(700, 12000)
    }

    @Test
    fun gif() {
        size(ascii("GIF89a") + bytes(0x20, 0x03, 0x58, 0x02) + imageData) shouldBe PageSize(800, 600)
    }

    private fun riff(chunk: String, payload: ByteArray) = ascii("RIFF") + bytes(0, 0, 0, 0) + ascii("WEBP") +
        ascii(chunk) + bytes(0, 0, 0, 0) + payload + imageData

    @Test
    fun `lossy webp`() {
        // Frame tag, start code, 14-bit width and height
        val payload = bytes(0, 0, 0, 0x9D, 0x01, 0x2A, 0x20, 0x03, 0xB0, 0x36)
        size(riff("VP8 ", payload)) shouldBe PageSize(800, 14000)
    }

    @Test
    fun `lossless webp`() {
        // width - 1 = 719 and height - 1 = 9999 packed in 14 bits each
        val packed = 719 or (9999 shl 14)
        val payload = bytes(0x2F, packed and 0xFF, (packed shr 8) and 0xFF, (packed shr 16) and 0xFF, packed ushr 24)
        size(riff("VP8L", payload)) shouldBe PageSize(720, 10000)
    }

    @Test
    fun `extended webp uses the canvas size`() {
        // Flags and reserved, then width - 1 = 1599 and height - 1 = 19999 in 24 bits
        val payload = bytes(0x10, 0, 0, 0, 0x3F, 0x06, 0x00, 0x1F, 0x4E, 0x00)
        size(riff("VP8X", payload)) shouldBe PageSize(1600, 20000)
    }

    @Test
    fun `jpeg size is read past metadata segments`() {
        val exif = ByteArray(5000) { 0x45 }
        val jpeg = bytes(0xFF, 0xD8) +
            // APP0, then a large APP1 that runs well past the bytes read up front
            bytes(0xFF, 0xE0, 0, 16) + ByteArray(14) +
            bytes(0xFF, 0xE1, (exif.size + 2) shr 8, (exif.size + 2) and 0xFF) + exif +
            // DHT shares the SOF range and must be skipped
            bytes(0xFF, 0xC4, 0, 4, 0, 0) +
            // Fill bytes before SOF2 (progressive): precision, height 9000, width 690
            bytes(0xFF, 0xFF, 0xC2, 0, 17, 8, 0x23, 0x28, 0x02, 0xB2) + ByteArray(12) + imageData
        size(jpeg) shouldBe PageSize(690, 9000)
    }

    @Test
    fun `jpeg segment bodies are skipped, not read`() {
        // Close to the 16-bit limit on a segment's length
        val exif = ByteArray(60_000)
        val jpeg = bytes(0xFF, 0xD8) +
            bytes(0xFF, 0xE1, (exif.size + 2) shr 8 and 0xFF, (exif.size + 2) and 0xFF) + exif +
            bytes(0xFF, 0xC0, 0, 17, 8, 0x05, 0xDC, 0x03, 0x20) + ByteArray(12) + imageData
        val input = CountingStream(jpeg)
        ImageHeader.readSize(input) shouldBe PageSize(800, 1500)
        // The 30 bytes read up front (holding the APP1 header), then the 9 bytes of the SOF header up to the width;
        // the 60 KB body went through skip()
        input.bytesRead shouldBe 30 + 9
    }

    @Test
    fun `scan data before a frame header gives no size`() {
        size(bytes(0xFF, 0xD8, 0xFF, 0xDA, 0, 2) + imageData) shouldBe null
    }

    @Test
    fun `truncated and unknown files give no size`() {
        size(bytes(0xFF, 0xD8, 0xFF, 0xE1, 0x10, 0x00) + ByteArray(20)) shouldBe null
        size(ascii("GIF8")) shouldBe null
        size(ByteArray(0)) shouldBe null
        // AVIF (ISO BMFF) isn't read
        size(bytes(0, 0, 0, 0x20) + ascii("ftypavif") + imageData) shouldBe null
    }

    private class CountingStream(data: ByteArray) : InputStream() {
        private val input = ByteArrayInputStream(data)
        var bytesRead = 0
            private set

        override fun read(): Int = input.read().also { if (it >= 0) bytesRead++ }

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            input.read(b, off, len).also { if (it > 0) bytesRead += it }

        override fun skip(n: Long): Long = input.skip(n)
    }
}
