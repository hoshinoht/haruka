package haruka.chapter.dedup.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.interactor.GetFavorites

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class DedupSettingsViewModel(
    private val getFavorites: GetFavorites,
    private val chapterRepository: ChapterRepository,
) : ViewModel() {

    private val _suggestions = MutableStateFlow<List<String>?>(null)

    /** Every scanlator group across the library, or null until [loadSuggestions] finishes. */
    val suggestions: StateFlow<List<String>?> = _suggestions.asStateFlow()

    private var loading: Job? = null

    /** Loads once per screen visit, on first need: one indexed DISTINCT query per library entry. */
    fun loadSuggestions() {
        if (loading != null) return
        loading = viewModelScope.launch(Dispatchers.IO) {
            val scanlators = HashSet<String>()
            for (manga in getFavorites.await()) {
                scanlators += chapterRepository.getScanlatorsByMangaId(manga.id)
            }
            _suggestions.value = DedupRanking.groups(scanlators)
        }
    }
}
