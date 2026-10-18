package haruka.related

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.tachiyomi.source.Source
import haruka.preference.HarukaPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import logcat.LogPriority
import mihon.domain.manga.model.toDomainManga
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga

/** Loads the source's related titles once per manga screen, so scrolling the row away never refetches. */
@AssistedInject
class RelatedMangaViewModel(
    @Assisted manga: Manga,
    @Assisted source: Source,
    preferences: HarukaPreferences,
    private val networkToLocalManga: NetworkToLocalManga,
    private val getManga: GetManga,
) : ViewModel() {

    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        fun create(manga: Manga, source: Source): RelatedMangaViewModel
    }

    private val mutableRelated = MutableStateFlow<List<Manga>>(emptyList())
    val related: StateFlow<List<Manga>> = mutableRelated.asStateFlow()

    init {
        if (preferences.relatedManga.get()) {
            viewModelScope.launch(Dispatchers.IO) { load(manga, source) }
        }
    }

    private suspend fun load(manga: Manga, source: Source) {
        try {
            val titles = RelatedMangaSource.fetch(source, manga.toSManga())
                .map { it.toDomainManga(source.id) }
                .distinctBy { it.url }
                .filterNot { it.url == manga.url }
            if (titles.isNotEmpty()) mutableRelated.value = networkToLocalManga(titles)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // An extension may hand back half-initialised SManga (lateinit url/title); never break the screen.
            logcat(LogPriority.ERROR, e) { "Could not show related titles for ${manga.url}" }
        }
    }

    /** Tracks library changes of a shown title, like the global search rows do. */
    @Composable
    fun getManga(initialManga: Manga): State<Manga> {
        return produceState(initialValue = initialManga) {
            getManga.subscribe(initialManga.url, initialManga.source)
                .filterNotNull()
                .collectLatest { value = it }
        }
    }
}
