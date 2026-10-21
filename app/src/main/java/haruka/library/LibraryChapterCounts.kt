package haruka.library

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import haruka.chapter.dedup.DedupSettings
import haruka.chapter.dedup.DedupSurfaces
import haruka.chapter.dedup.SeriesCounts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.library.model.LibraryManga
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * Library chapter counts that match the chapter list: with duplicate copies grouped, a series with 405 uploads of
 * 155 chapters counts 155, and a chapter read in any copy counts as read.
 *
 * Counting a series reads its chapters and dedupes them, so it only happens for a series whose fingerprint changed:
 * its uploads, read count, latest upload, last fetch, or the dedup settings that apply to it. A library update or a
 * finished chapter therefore recounts only the series it touched. The counts are kept in a fork-owned file, so app
 * start reuses them instead of recounting the library; a series shows raw counts only until its first count.
 */
@SingleIn(AppScope::class)
class LibraryChapterCounts internal constructor(
    private val dedup: DedupSurfaces,
    private val settings: DedupSettings,
    private val file: File,
) {

    @Inject
    constructor(context: Context, dedup: DedupSurfaces, settings: DedupSettings) :
        this(dedup, settings, File(context.filesDir, "haruka/chapter_counts.bin"))

    internal data class Stamp(
        val total: Long,
        val read: Long,
        val latestUpload: Long,
        val fetchedAt: Long,
        val settingsKey: Int,
    )

    internal class Entry(val stamp: Stamp, val counts: SeriesCounts)

    private val mutex = Mutex()
    private var loaded = false

    // Published entries; replaced as a whole, at most once per counting pass
    private val entries = MutableStateFlow<Map<Long, Entry>>(emptyMap())
    private val inflatedIds = MutableStateFlow<Set<Long>>(emptySet())

    /** Series whose upload count is inflated, for the "Inflated counts" shelf. */
    val inflated: StateFlow<Set<Long>> = inflatedIds

    /**
     * [library] with each counted series' totals replaced, so filters, sorts, badges and shelves downstream see the
     * logical counts. Counting runs while the result is collected; library states that arrive during a pass are
     * folded into the next one.
     */
    fun apply(library: Flow<List<LibraryManga>>): Flow<List<LibraryManga>> = channelFlow {
        val pending = MutableStateFlow<Pair<List<LibraryManga>, (Long) -> Int>?>(null)
        launch(Dispatchers.IO) {
            pending.filterNotNull().collect { (list, keys) -> refresh(list, keys) }
        }
        combine(library, settings.countsKeys(), entries) { list, keys, counted ->
            pending.value = list to keys
            list.map { manga ->
                counted[manga.id]?.takeIf { it.stamp == manga.stamp(keys) }?.let { manga.with(it.counts) } ?: manga
            }
        }.collect { send(it) }
    }

    private suspend fun refresh(library: List<LibraryManga>, keys: (Long) -> Int) = mutex.withLock {
        if (!loaded) {
            loaded = true
            load()?.let { publish(it) }
        }
        val current = entries.value
        val result = HashMap<Long, Entry>(library.size * 2)
        var changed = current.size != library.size
        for (manga in library) {
            val stamp = manga.stamp(keys)
            val known = current[manga.id]
            if (known?.stamp == stamp) {
                result[manga.id] = known
                continue
            }
            result[manga.id] = Entry(stamp, dedup.seriesCounts(manga.manga))
            changed = true
        }
        if (!changed) return@withLock
        publish(result)
        save(result)
    }

    private fun publish(result: Map<Long, Entry>) {
        entries.value = result
        inflatedIds.value = result.filterValues { it.counts.inflated }.keys
    }

    private fun LibraryManga.stamp(keys: (Long) -> Int) = Stamp(
        totalChapters,
        readCount,
        latestUpload?.toEpochMilliseconds() ?: NONE,
        chapterFetchedAt?.toEpochMilliseconds() ?: NONE,
        keys(id),
    )

    private fun LibraryManga.with(counts: SeriesCounts): LibraryManga =
        if (!counts.grouped) this else copy(totalChapters = counts.total, readCount = counts.read)

    private fun load(): Map<Long, Entry>? = try {
        if (!file.exists()) {
            null
        } else {
            DataInputStream(file.inputStream().buffered()).use { readEntries(it) }
        }
    } catch (e: Exception) {
        logcat(LogPriority.WARN, e) { "Chapter counts cache unreadable; recounting" }
        null
    }

    private fun save(result: Map<Long, Entry>) {
        try {
            file.parentFile?.mkdirs()
            val temp = File(file.path + ".tmp")
            DataOutputStream(temp.outputStream().buffered()).use { writeEntries(it, result) }
            temp.renameTo(file)
        } catch (e: Exception) {
            // Only a cache: the next start recounts
            logcat(LogPriority.WARN, e) { "Chapter counts cache not saved" }
        }
    }

    internal companion object {
        private const val NONE = Long.MIN_VALUE
        private const val FORMAT = 1

        internal fun writeEntries(out: DataOutputStream, entries: Map<Long, Entry>) {
            out.writeInt(FORMAT)
            out.writeInt(entries.size)
            for ((id, entry) in entries) {
                out.writeLong(id)
                with(entry.stamp) {
                    out.writeLong(total)
                    out.writeLong(read)
                    out.writeLong(latestUpload)
                    out.writeLong(fetchedAt)
                    out.writeInt(settingsKey)
                }
                with(entry.counts) {
                    out.writeLong(total)
                    out.writeLong(read)
                    out.writeBoolean(grouped)
                    out.writeBoolean(inflated)
                }
            }
        }

        internal fun readEntries(input: DataInputStream): Map<Long, Entry>? {
            if (input.readInt() != FORMAT) return null
            val size = input.readInt()
            val result = HashMap<Long, Entry>(size * 2)
            repeat(size) {
                val id = input.readLong()
                val stamp =
                    Stamp(input.readLong(), input.readLong(), input.readLong(), input.readLong(), input.readInt())
                val counts = SeriesCounts(input.readLong(), input.readLong(), input.readBoolean(), input.readBoolean())
                result[id] = Entry(stamp, counts)
            }
            return result
        }
    }
}
