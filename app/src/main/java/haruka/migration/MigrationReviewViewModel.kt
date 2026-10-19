package haruka.migration

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import mihon.feature.migration.list.models.MigratingManga
import mihon.feature.migration.list.models.MigratingManga.SearchResult
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.manga.interactor.GetManga

/**
 * Match confidence for every entry of a bulk migration list, plus the review step that runs before Migrate all or
 * Copy all. Shared by the list rows and the review sheet through the migration screen's view model store.
 * Acceptances live only here and are dropped whenever an entry's result changes.
 */
@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class MigrationReviewViewModel(
    private val getChaptersByMangaId: GetChaptersByMangaId,
    private val getManga: GetManga,
) : ViewModel() {

    private val mutableState = MutableStateFlow(State())
    val state: StateFlow<State> = mutableState.asStateFlow()

    // Touched only from the main thread (composition effects).
    private val trackers = HashMap<Long, Job>()

    /** Follows the list's entries: scores new ones as their results arrive and forgets removed ones. */
    fun track(items: List<MigratingManga>) {
        val ids = items.map { it.manga.id }
        val idSet = ids.toHashSet()
        (trackers.keys - idSet).forEach { trackers.remove(it)?.cancel() }
        mutableState.update { state ->
            state.copy(
                order = ids,
                entries = state.entries.filterKeys { it in idSet },
                accepted = state.accepted intersect idSet,
            )
        }
        items.forEach { item -> trackers.getOrPut(item.manga.id) { launchTracker(item) } }
    }

    fun confidenceOf(mangaId: Long): Flow<MatchConfidence?> =
        state.map { it.entries[mangaId]?.confidence }.distinctUntilChanged()

    private fun launchTracker(item: MigratingManga) = viewModelScope.launch(Dispatchers.IO) {
        val id = item.manga.id
        var furthestRead: Double? = null
        var furthestReadLoaded = false
        item.searchResult.collectLatest { result ->
            mutableState.update { state ->
                if (id !in state.order) return@update state
                state.copy(entries = state.entries + (id to Entry(item, result, null)), accepted = state.accepted - id)
            }
            if (result !is SearchResult.Success) return@collectLatest

            if (!furthestReadLoaded) {
                furthestRead = getChaptersByMangaId.await(id)
                    .filter { it.read && it.chapterNumber >= 0 }
                    .maxOfOrNull { it.chapterNumber }
                furthestReadLoaded = true
            }
            // The stored copy may have details fetched after the search; never fetch from the source here.
            val candidateAuthor = getManga.await(result.manga.id)?.author ?: result.manga.author
            val confidence = scoreMatch(
                MatchInput(
                    originalTitle = item.manga.title,
                    candidateTitle = result.manga.title,
                    originalAuthor = item.manga.author,
                    candidateAuthor = candidateAuthor,
                    furthestReadChapter = furthestRead,
                    candidateLatestChapter = result.latestChapter,
                ),
            )
            mutableState.update { state ->
                if (state.entries[id]?.result != result) return@update state
                state.copy(entries = state.entries + (id to Entry(item, result, confidence)))
            }
        }
    }

    /** Returns true when the existing dialog can open right away, as upstream; otherwise starts the review. */
    fun requestBulkAction(copy: Boolean): Boolean {
        val state = mutableState.value
        if (!state.busy && state.pending.isEmpty()) return true
        mutableState.update { it.copy(review = Review(copy, Stage.Deciding)) }
        return false
    }

    /**
     * Advances a waiting review after a state change. Returns the copy flag when the existing dialog should open now.
     */
    fun resolve(): Boolean? {
        val state = mutableState.value
        val review = state.review ?: return null
        val next = when (val stage = review.stage) {
            Stage.Deciding -> when {
                state.busy -> return null
                state.pending.isEmpty() -> null
                else -> review.copy(stage = Stage.Reviewing)
            }
            Stage.Reviewing -> return null
            is Stage.Skipping -> {
                if (stage.ids.any { it in state.entries }) return null
                null
            }
        }
        mutableState.update { it.copy(review = next) }
        return if (next == null) review.copy else null
    }

    fun toggleAccepted(mangaId: Long) {
        mutableState.update { state ->
            state.copy(accepted = if (mangaId in state.accepted) state.accepted - mangaId else state.accepted + mangaId)
        }
    }

    /** Accepts every flagged entry; the caller opens the existing dialog. */
    fun acceptAll(): Boolean? {
        val review = mutableState.value.review ?: return null
        mutableState.update { state ->
            state.copy(accepted = state.accepted + state.flagged.map { it.mangaId }, review = null)
        }
        return review.copy
    }

    /** Ends the review keeping only accepted flagged entries; returns the entries the caller must skip. */
    fun continueWithAccepted(): List<Long> {
        val skipped = mutableState.value.pending.map { it.mangaId }
        mutableState.update { state ->
            state.copy(review = state.review?.copy(stage = Stage.Skipping(skipped.toSet())))
        }
        return skipped
    }

    fun dismissReview() {
        mutableState.update { it.copy(review = null) }
    }

    data class Entry(val item: MigratingManga, val result: SearchResult, val confidence: MatchConfidence?) {
        val mangaId: Long get() = item.manga.id
        val isFlagged: Boolean
            get() = result is SearchResult.Success && confidence != null &&
                confidence.level != MatchConfidence.Level.HIGH
    }

    data class Review(val copy: Boolean, val stage: Stage)

    sealed interface Stage {
        /** Waiting for results or scores before deciding whether to review at all. */
        data object Deciding : Stage
        data object Reviewing : Stage

        /** Waiting for the skipped entries to leave the list, so the dialog counts are right. */
        data class Skipping(val ids: Set<Long>) : Stage
    }

    data class State(
        val order: List<Long> = emptyList(),
        val entries: Map<Long, Entry> = emptyMap(),
        val accepted: Set<Long> = emptySet(),
        val review: Review? = null,
    ) {
        /** Flagged entries in list order. */
        val flagged: List<Entry> by lazy { order.mapNotNull { entries[it] }.filter { it.isFlagged } }

        /** Flagged entries not accepted yet. */
        val pending: List<Entry> by lazy { flagged.filter { it.mangaId !in accepted } }

        /** A result or its score is still on its way. */
        val busy: Boolean by lazy {
            entries.values.any {
                it.result is SearchResult.Searching || (it.result is SearchResult.Success && it.confidence == null)
            }
        }

        /** Entries that would still migrate after "Continue with accepted". */
        val acceptedOrUnflagged: Int by lazy {
            entries.values.count { it.result is SearchResult.Success && (!it.isFlagged || it.mangaId in accepted) }
        }
    }
}
