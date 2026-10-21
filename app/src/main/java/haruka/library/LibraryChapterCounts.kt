package haruka.library

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
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
import tachiyomi.domain.library.model.LibraryManga

/**
 * Library chapter counts that match the chapter list: with duplicate copies grouped, a series with 405 uploads of
 * 101 chapters counts 101, and a chapter read in any copy counts as read. Each series is counted off the main thread
 * once, then again only when its uploads, read count, latest upload or last fetch change, so the library shows raw
 * counts for a moment only for a series that just changed. Changing a series' dedup rules takes effect the next time
 * its chapters change.
 */
@Inject
@SingleIn(AppScope::class)
class LibraryChapterCounts(private val dedup: DedupSurfaces) {

    private data class Stamp(val total: Long, val read: Long, val latestUpload: Long?, val fetchedAt: Long?)

    private class Entry(val stamp: Stamp, val counts: SeriesCounts)

    private val mutex = Mutex()
    private val entries = MutableStateFlow<Map<Long, Entry>>(emptyMap())

    private val inflatedIds = MutableStateFlow<Set<Long>>(emptySet())

    /** Series whose upload count is inflated, for the "Inflated counts" shelf. */
    val inflated: StateFlow<Set<Long>> = inflatedIds

    /**
     * [library] with each counted series' totals replaced, so filters, sorts, badges and shelves downstream see the
     * logical counts. Counting runs while the result is collected; intermediate library emissions are skipped.
     */
    fun apply(library: Flow<List<LibraryManga>>): Flow<List<LibraryManga>> = channelFlow {
        val latest = MutableStateFlow<List<LibraryManga>?>(null)
        launch(Dispatchers.IO) { latest.filterNotNull().collect { refresh(it) } }
        combine(library, entries) { list, counted ->
            latest.value = list
            list.map { manga ->
                counted[manga.id]?.takeIf { it.stamp == manga.stamp() }?.let { manga.with(it.counts) } ?: manga
            }
        }.collect { send(it) }
    }

    private suspend fun refresh(library: List<LibraryManga>) = mutex.withLock {
        val result = HashMap(entries.value)
        result.keys.retainAll(library.mapTo(HashSet()) { it.id })
        var pending = 0
        for (manga in library) {
            val stamp = manga.stamp()
            if (result[manga.id]?.stamp == stamp) continue
            result[manga.id] = Entry(stamp, dedup.seriesCounts(manga.manga))
            // Publish in batches so a first full pass fills the library in as it goes
            if (++pending == BATCH) {
                publish(result)
                pending = 0
            }
        }
        publish(result)
    }

    private fun publish(result: Map<Long, Entry>) {
        entries.value = HashMap(result)
        inflatedIds.value = result.filterValues { it.counts.inflated }.keys
    }

    private fun LibraryManga.stamp() = Stamp(
        totalChapters,
        readCount,
        latestUpload?.toEpochMilliseconds(),
        chapterFetchedAt?.toEpochMilliseconds(),
    )

    private fun LibraryManga.with(counts: SeriesCounts): LibraryManga =
        if (!counts.grouped) this else copy(totalChapters = counts.total, readCount = counts.read)

    private companion object {
        const val BATCH = 50
    }
}
