package haruka.updateerrors

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import haruka.selection.RangeSelection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import kotlin.time.Duration.Companion.seconds

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class UpdateErrorsViewModel(
    store: UpdateErrorStore,
    getLibraryManga: GetLibraryManga,
    sourceManager: SourceManager,
) : ViewModel() {

    private val selection = MutableStateFlow(RangeSelection<Long>())

    private val groups = combine(store.errors, getLibraryManga.subscribe()) { errors, library ->
        val libraryManga = library.associateBy({ it.manga.id }, { it.manga })
        // Entries that left the library since (e.g. migrated away) no longer need attention.
        groupByMessage(errors.filter { it.mangaId in libraryManga })
            .map { (message, entries) ->
                Group(
                    message = message,
                    entries = entries.map {
                        Entry(libraryManga.getValue(it.mangaId), sourceManager.getOrStub(it.sourceId).name)
                    },
                )
            }
    }

    val state: StateFlow<State> = combine(groups, selection) { groups, selection ->
        val ordered = groups.flatMap { group -> group.entries.map { it.manga.id } }
        State(
            isLoading = false,
            groups = groups,
            ordered = ordered,
            selection =
            selection.selected intersect ordered.toSet(),
        )
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5.seconds), State())

    fun toggleSelection(manga: Manga) {
        selection.update { it.toggle(manga.id) }
    }

    fun selectRange(manga: Manga) {
        selection.update { it.selectRange(state.value.ordered, manga.id) }
    }

    fun selectAll() {
        selection.update { it.selectAll(state.value.ordered) }
    }

    fun clearSelection() {
        selection.update { it.clear() }
    }

    @Immutable
    data class Group(val message: String?, val entries: List<Entry>)

    @Immutable
    data class Entry(val manga: Manga, val sourceName: String)

    @Immutable
    data class State(
        val isLoading: Boolean = true,
        val groups: List<Group> = emptyList(),
        /** Entry ids in display order, for range selection. */
        val ordered: List<Long> = emptyList(),
        val selection: Set<Long> = emptySet(),
    ) {
        val selectionMode: Boolean
            get() = selection.isNotEmpty()
    }
}
