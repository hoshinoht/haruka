package haruka.library

import haruka.chapter.dedup.SeriesCounts
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

class LibraryChapterCountsTest {

    @Test
    fun `saved counts read back unchanged, so app start can reuse them`() {
        val entries = mapOf(
            848L to LibraryChapterCounts.Entry(
                LibraryChapterCounts.Stamp(405, 0, 1_790_000_000_000, Long.MIN_VALUE, -17),
                SeriesCounts(total = 155, read = 0, grouped = true, inflated = true),
            ),
            6L to LibraryChapterCounts.Entry(
                LibraryChapterCounts.Stamp(86, 40, Long.MIN_VALUE, 1_780_000_000_000, 3),
                SeriesCounts(total = 44, read = 21, grouped = false, inflated = false),
            ),
        )
        val bytes = ByteArrayOutputStream().also { out ->
            DataOutputStream(out).use { LibraryChapterCounts.writeEntries(it, entries) }
        }.toByteArray()
        val read = DataInputStream(ByteArrayInputStream(bytes)).use { LibraryChapterCounts.readEntries(it) }!!
        read.keys shouldBe entries.keys
        for ((id, entry) in entries) {
            read.getValue(id).stamp shouldBe entry.stamp
            read.getValue(id).counts shouldBe entry.counts
        }
    }

    @Test
    fun `a file from another format version is ignored`() {
        val bytes = ByteArrayOutputStream().also { out -> DataOutputStream(out).use { it.writeInt(99) } }.toByteArray()
        DataInputStream(ByteArrayInputStream(bytes)).use { LibraryChapterCounts.readEntries(it) } shouldBe null
    }
}
