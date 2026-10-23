package haruka.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.track.interactor.AddTracks
import eu.kanade.presentation.browse.components.RemoveMangaDialog
import eu.kanade.presentation.category.components.ChangeCategoryDialog
import eu.kanade.presentation.manga.DuplicateMangaDialog
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.ui.category.CategoryScreen
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.util.removeCovers
import haruka.di.harukaGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import mihon.core.metro.AppCoroutineScope
import mihon.feature.migration.dialog.MigrateMangaDialog
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.core.common.preference.mapAsCheckboxState
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.interactor.SetMangaDefaultChapterFlags
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetDuplicateLibraryManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.model.MangaWithChapterCount
import tachiyomi.domain.source.service.SourceManager
import kotlin.time.Clock

/**
 * Long-press add/remove of a single entry, for every manga grid that lacks it upstream.
 *
 * Mirrors BrowseSourceViewModel's behaviour exactly (duplicate check, default category, category picker,
 * remove confirmation) so the gesture means the same thing everywhere.
 */
@Inject
@SingleIn(AppScope::class)
class LibraryToggle(
    @AppCoroutineScope private val appScope: CoroutineScope,
    private val sourceManager: SourceManager,
    private val libraryPreferences: LibraryPreferences,
    private val coverCache: CoverCache,
    private val getDuplicateLibraryManga: GetDuplicateLibraryManga,
    private val getCategories: GetCategories,
    private val setMangaCategories: SetMangaCategories,
    private val setMangaDefaultChapterFlags: SetMangaDefaultChapterFlags,
    private val updateManga: UpdateManga,
    private val addTracks: AddTracks,
) {

    sealed interface Dialog {
        data class Remove(val manga: Manga) : Dialog
        data class Duplicate(val manga: Manga, val duplicates: List<MangaWithChapterCount>) : Dialog
        data class Migrate(val target: Manga, val current: Manga) : Dialog
        data class ChooseCategories(
            val manga: Manga,
            val initialSelection: List<CheckboxState.State<Category>>,
        ) : Dialog
    }

    /** Toggles [manga]'s library state, returning a dialog when the user has to decide something. */
    suspend fun onLongClick(manga: Manga): Dialog? {
        if (manga.favorite) return Dialog.Remove(manga)
        val duplicates = getDuplicateLibraryManga(manga)
        if (duplicates.isNotEmpty()) return Dialog.Duplicate(manga, duplicates)
        return add(manga)
    }

    /** Adds [manga] to its default category, or asks for categories when no default is set. */
    suspend fun add(manga: Manga): Dialog? {
        val categories = userCategories()
        val defaultCategoryId = libraryPreferences.defaultCategory.get().toLong()
        val defaultCategory = categories.find { it.id == defaultCategoryId }
        return when {
            defaultCategory != null -> {
                setFavorite(manga, listOf(defaultCategory.id))
                null
            }
            defaultCategoryId == 0L || categories.isEmpty() -> {
                setFavorite(manga, emptyList())
                null
            }
            else -> {
                val preselected = getCategories.await(manga.id).map { it.id }
                Dialog.ChooseCategories(manga, categories.mapAsCheckboxState { it.id in preselected })
            }
        }
    }

    fun setFavorite(manga: Manga, categoryIds: List<Long>) {
        appScope.launch {
            setMangaCategories.await(mangaId = manga.id, categoryIds = categoryIds)
            setMangaDefaultChapterFlags.await(manga)
            addTracks.bindEnhancedTrackers(manga, sourceManager.getOrStub(manga.source))
            updateManga.await(MangaUpdate(manga.id) { favoriteAt = Clock.System.now() })
        }
    }

    fun remove(manga: Manga) {
        appScope.launch {
            manga.removeCovers(coverCache)
            updateManga.await(MangaUpdate(manga.id) { favoriteAt = null })
        }
    }

    private suspend fun userCategories(): List<Category> =
        getCategories.await().filterNot { it.isSystemCategory }
}

/** Long-press handler plus the dialogs it may open. Call [Dialogs] once in the screen's content. */
class LibraryToggleHandle internal constructor(
    val onLongClick: (Manga) -> Unit,
    val Dialogs: @Composable () -> Unit,
)

@Composable
fun rememberLibraryToggle(screen: Screen): LibraryToggleHandle {
    val context = LocalContext.current
    val toggle = remember { context.harukaGraph.libraryToggle }
    val navigator = LocalNavigator.currentOrThrow
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var dialog by remember { mutableStateOf<LibraryToggle.Dialog?>(null) }

    return LibraryToggleHandle(
        onLongClick = { manga ->
            scope.launch {
                dialog = toggle.onLongClick(manga)
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            }
        },
        Dialogs = {
            val dismiss = { dialog = null }
            when (val current = dialog) {
                is LibraryToggle.Dialog.Remove -> RemoveMangaDialog(
                    onDismissRequest = dismiss,
                    onConfirm = { toggle.remove(current.manga) },
                    mangaToRemove = current.manga,
                )
                is LibraryToggle.Dialog.Duplicate -> DuplicateMangaDialog(
                    duplicates = current.duplicates,
                    onDismissRequest = dismiss,
                    onConfirm = { scope.launch { dialog = toggle.add(current.manga) } },
                    onOpenManga = { navigator.push(MangaScreen(it.id)) },
                    onMigrate = { dialog = LibraryToggle.Dialog.Migrate(current.manga, it) },
                )
                is LibraryToggle.Dialog.Migrate -> screen.MigrateMangaDialog(
                    current = current.current,
                    target = current.target,
                    onClickTitle = { navigator.push(MangaScreen(current.current.id)) },
                    onDismissRequest = dismiss,
                )
                is LibraryToggle.Dialog.ChooseCategories -> ChangeCategoryDialog(
                    initialSelection = current.initialSelection,
                    onDismissRequest = dismiss,
                    onEditCategories = { navigator.push(CategoryScreen()) },
                    onConfirm = { include, _ -> toggle.setFavorite(current.manga, include) },
                )
                null -> Unit
            }
        },
    )
}
