package haruka.library.shelves

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import haruka.chapter.dedup.DedupSurfaces
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tachiyomi.domain.library.model.LibraryManga

/**
 * Library series whose upload count is inflated (see [haruka.library.isCountInflated]), for the "Inflated counts"
 * shelf. Checking a series reads its chapters and dedupes them with its own rules, so each series is checked once and
 * again only when its chapter count, latest upload or last fetch changes. Changing dedup rules takes effect for a
 * series the next time its chapters change.
 */
@Inject
@SingleIn(AppScope::class)
class InflatedCountsStore(private val dedup: DedupSurfaces) {

    private data class Stamp(val total: Long, val latestUpload: Long?, val fetchedAt: Long?)

    private val mutex = Mutex()
    private val stamps = HashMap<Long, Stamp>()
    private val inflated = MutableStateFlow<Set<Long>>(emptySet())

    val ids: StateFlow<Set<Long>> = inflated

    /** Checks the series of [library] that are new or changed, and forgets the ones no longer in it. */
    suspend fun refresh(library: Collection<LibraryManga>) = mutex.withLock {
        val result = inflated.value.toMutableSet()
        val present = HashSet<Long>(library.size * 2)
        for (entry in library) {
            present += entry.id
            val stamp = Stamp(
                entry.totalChapters,
                entry.latestUpload?.toEpochMilliseconds(),
                entry.chapterFetchedAt?.toEpochMilliseconds(),
            )
            if (stamps[entry.id] == stamp) continue
            if (dedup.isCountInflated(entry.manga)) result += entry.id else result -= entry.id
            stamps[entry.id] = stamp
        }
        stamps.keys.retainAll(present)
        result.retainAll(present)
        inflated.value = result
    }
}
