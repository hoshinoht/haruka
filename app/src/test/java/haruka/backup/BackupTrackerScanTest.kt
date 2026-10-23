package haruka.backup

import eu.kanade.tachiyomi.data.backup.BackupReader
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupChapter
import eu.kanade.tachiyomi.data.backup.models.BackupHistory
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupSource
import eu.kanade.tachiyomi.data.backup.models.BackupTracking
import io.kotest.matchers.shouldBe
import kotlinx.serialization.protobuf.ProtoBuf
import okio.Buffer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import kotlin.random.Random

class BackupTrackerScanTest {

    @Test
    fun `scan finds the same tracker ids as decoding every entry`() {
        repeat(50) { seed ->
            val bytes = encode(randomBackup(Random(seed)))

            scan(bytes) shouldBe decodedTrackerIds(bytes)
        }
    }

    @Test
    fun `a backup without entries has no tracker ids`() {
        scan(
            encode(Backup(emptyList(), backupSources = listOf(BackupSource("MangaDex", 2499283573021220255)))),
        ) shouldBe
            emptySet()
    }

    @Test
    fun `a truncated backup falls back to decoding`() {
        val bytes = encode(randomBackup(Random(7)))

        for (cut in listOf(1, bytes.size / 3, bytes.size / 2, bytes.size - 1)) {
            scan(bytes.copyOf(cut)) shouldBe null
        }
    }

    @Test
    fun `a tracking element without a syncId falls back, as decoding it fails`() {
        // BackupManga { source = 1, url = "u", tracking = [BackupTracking { libraryId = 5 }] }
        val entry = Buffer().writeByte(0x08).writeByte(1)
            .writeByte(0x12).writeByte(1).writeUtf8("u")
            .writeByte(0x92).writeByte(0x01).writeByte(2).writeByte(0x10).writeByte(5)
            .readByteArray()
        val bytes = Buffer().writeByte(0x0a).writeByte(entry.size).write(entry).readByteArray()

        assertThrows<Exception> { ProtoBuf.decodeFromByteArray(Backup.serializer(), bytes) }
        scan(bytes) shouldBe null
    }

    @Test
    fun `an entry field with the wrong wire type falls back`() {
        // Backup { backupManga = varint 3 } is not a message
        scan(byteArrayOf(0x08, 0x03)) shouldBe null
    }

    @Test
    fun `a JSON backup falls back, so it keeps its own error`() {
        scan("""{"backupManga":[]}""".toByteArray()) shouldBe null
    }

    private fun scan(bytes: ByteArray) = scanTrackerIds(Buffer().write(bytes))

    /** What BackupFileValidator collected before the scan: every entry decoded. */
    private fun decodedTrackerIds(bytes: ByteArray): Set<Int> {
        val reader = BackupReader(Buffer().write(bytes), ProtoBuf)
        val ids = mutableSetOf<Int>()
        while (reader.nextEntry()) reader.readEntry().tracking.mapTo(ids) { it.syncId }
        return ids
    }

    private fun encode(backup: Backup) = ProtoBuf.encodeToByteArray(Backup.serializer(), backup)

    private fun randomBackup(random: Random) = Backup(
        backupManga = List(random.nextInt(0, 40)) { i ->
            BackupManga(
                source = random.nextLong(),
                url = "/manga/$i",
                title = "Title $i",
                description = "x".repeat(random.nextInt(0, 400)),
                genre = List(random.nextInt(0, 5)) { "genre $it" },
                chapters = List(random.nextInt(0, 300)) { c ->
                    BackupChapter(url = "/c/$i/$c", name = "Chapter $c", chapterNumber = c.toFloat())
                },
                categories = List(random.nextInt(0, 3)) { it.toLong() },
                tracking = List(random.nextInt(0, 4)) {
                    BackupTracking(
                        // Negative ids too: they encode as ten-byte varints
                        syncId = random.nextInt(-3, 12),
                        libraryId = random.nextLong(),
                        title = "Tracked $i",
                        score = random.nextFloat(),
                    )
                },
                history = List(random.nextInt(0, 20)) { h -> BackupHistory("/c/$i/$h", random.nextLong()) },
                memo = random.nextBytes(random.nextInt(0, 16)),
            )
        },
        backupCategories = listOf(BackupCategory("Reading", 0, 0)),
        backupSources = listOf(BackupSource("Source", 1)),
    )
}
