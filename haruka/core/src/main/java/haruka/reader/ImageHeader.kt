package haruka.reader

import java.io.InputStream

/**
 * Reads a page's pixel size from the start of its file, without decoding or buffering the image. PNG, GIF and WebP
 * sizes sit in the first 30 bytes. JPEG keeps its size in the frame header after the metadata segments, so those are
 * walked by their 4-byte headers and their bodies skipped (a seek on a file). AVIF, HEIF and JPEG XL give null.
 *
 * Not the platform's bounds decode: Skia copies a whole WebP stream into memory just to read its header.
 */
object ImageHeader {

    private const val PREFIX_SIZE = 30

    fun readSize(input: InputStream): PageSize? {
        val head = ByteArray(PREFIX_SIZE)
        val size = input.readUpTo(head)
        return when {
            size >= 24 && head.isPng() -> PageSize(head.int32be(16), head.int32be(20))
            size >= 10 && head.ascii(0, "GIF8") -> PageSize(head.u16le(6), head.u16le(8))
            size >= 16 && head.ascii(0, "RIFF") && head.ascii(8, "WEBP") -> webpSize(head, size)
            size >= 2 && head.u8(0) == 0xFF && head.u8(1) == 0xD8 -> jpegSize(HeadThenStream(head, size, input))
            else -> null
        }
    }

    private fun ByteArray.isPng(): Boolean =
        u8(0) == 0x89 && ascii(1, "PNG") && u8(4) == 0x0D && u8(5) == 0x0A && u8(6) == 0x1A && u8(7) == 0x0A &&
            ascii(12, "IHDR")

    private fun webpSize(head: ByteArray, size: Int): PageSize? = when {
        // Lossy: frame tag (3 bytes), start code 9D 01 2A, then 14-bit width and height
        head.ascii(12, "VP8 ") && size >= 30 && head.u8(23) == 0x9D && head.u8(24) == 0x01 && head.u8(25) == 0x2A ->
            PageSize(head.u16le(26) and 0x3FFF, head.u16le(28) and 0x3FFF)
        // Lossless: signature 0x2F, then width - 1 and height - 1 packed in 14 bits each
        head.ascii(12, "VP8L") && size >= 25 && head.u8(20) == 0x2F -> {
            val b1 = head.u8(21)
            val b2 = head.u8(22)
            val b3 = head.u8(23)
            val b4 = head.u8(24)
            PageSize(
                width = 1 + (b1 or ((b2 and 0x3F) shl 8)),
                height = 1 + ((b2 shr 6) or (b3 shl 2) or ((b4 and 0x0F) shl 10)),
            )
        }
        // Extended: flags (4 bytes), then canvas width - 1 and height - 1 in 24 bits each
        head.ascii(12, "VP8X") && size >= 30 -> PageSize(1 + head.u24le(24), 1 + head.u24le(27))
        else -> null
    }

    private fun jpegSize(bytes: HeadThenStream): PageSize? {
        while (true) {
            if (bytes.read() != 0xFF) return null
            var marker = bytes.read()
            // Any number of 0xFF fill bytes may precede a marker
            while (marker == 0xFF) marker = bytes.read()
            when (marker) {
                // End of input, end of image, or scan data before any frame header
                -1, 0xD9, 0xDA -> return null
                // Markers without a length
                0x01, in 0xD0..0xD7 -> continue
            }
            val length = bytes.u16be()
            if (length < 2) return null
            if (marker.isStartOfFrame()) {
                if (bytes.read() == -1) return null // Sample precision
                val height = bytes.u16be()
                val width = bytes.u16be()
                return if (height < 0 || width < 0) null else PageSize(width, height)
            }
            if (!bytes.skip(length - 2L)) return null
        }
    }

    // SOF0 to SOF15, except DHT (C4), JPG (C8) and DAC (CC), which share the range
    private fun Int.isStartOfFrame(): Boolean = this in 0xC0..0xCF && this != 0xC4 && this != 0xC8 && this != 0xCC

    /** The bytes already read into [head], then the rest of [input]. */
    private class HeadThenStream(
        private val head: ByteArray,
        private val headSize: Int,
        private val input: InputStream,
    ) {
        // The SOI marker has been checked
        private var pos = 2

        fun read(): Int = if (pos < headSize) head[pos++].toInt() and 0xFF else input.read()

        fun u16be(): Int {
            val high = read()
            val low = read()
            return if (high < 0 || low < 0) -1 else (high shl 8) or low
        }

        /** False when the input ends first. */
        fun skip(count: Long): Boolean {
            val fromHead = minOf(count, (headSize - pos).toLong()).toInt()
            pos += fromHead
            var left = count - fromHead
            while (left > 0) {
                val skipped = input.skip(left)
                when {
                    skipped > 0 -> left -= skipped
                    // skip() may return 0 before the end; a read tells the two apart
                    input.read() == -1 -> return false
                    else -> left--
                }
            }
            return true
        }
    }

    private fun InputStream.readUpTo(buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val read = read(buffer, total, buffer.size - total)
            if (read < 0) break
            total += read
        }
        return total
    }

    private fun ByteArray.u8(at: Int): Int = this[at].toInt() and 0xFF

    private fun ByteArray.u16le(at: Int): Int = u8(at) or (u8(at + 1) shl 8)

    private fun ByteArray.u24le(at: Int): Int = u8(at) or (u8(at + 1) shl 8) or (u8(at + 2) shl 16)

    private fun ByteArray.int32be(at: Int): Int =
        (u8(at) shl 24) or (u8(at + 1) shl 16) or (u8(at + 2) shl 8) or u8(at + 3)

    private fun ByteArray.ascii(at: Int, text: String): Boolean =
        text.indices.all { this[at + it].toInt() == text[it].code }
}
