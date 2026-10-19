package haruka.related

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import eu.kanade.presentation.browse.components.GlobalSearchCardRow
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import haruka.i18n.HR
import haruka.library.rememberLibraryToggle
import mihon.app.di.appGraph
import tachiyomi.domain.manga.model.Manga
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Horizontal row of the titles [source] lists as related to [manga]. Emits nothing while loading, when the
 * source lacks the API, when it found nothing, or when the user turned the row off.
 */
@Composable
fun RelatedMangaRow(manga: Manga, source: Source) {
    val viewModel = assistedMetroViewModel<RelatedMangaViewModel, RelatedMangaViewModel.Factory> {
        create(manga = manga, source = source)
    }
    val related by viewModel.related.collectAsStateWithLifecycle()
    if (related.isEmpty()) return

    val navigator = LocalNavigator.currentOrThrow
    // The manga screen's presentation layer has no Screen receiver; the screen on top is the one showing this row.
    val libraryToggle = (navigator.lastItem as? Screen)?.let { rememberLibraryToggle(it) }
    libraryToggle?.Dialogs?.invoke()

    Column(modifier = Modifier.padding(top = MaterialTheme.padding.small)) {
        Text(
            text = stringResource(HR.strings.related_titles),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium),
        )
        GlobalSearchCardRow(
            titles = related,
            getManga = { viewModel.getManga(it) },
            onClick = { navigator.push(MangaScreen(it.id, true)) },
            onLongClick = { libraryToggle?.onLongClick?.invoke(it) },
        )
    }
}

@Composable
fun relatedMangaPreference(): Preference.PreferenceItem.SwitchPreference {
    val context = LocalContext.current
    return Preference.PreferenceItem.SwitchPreference(
        preference = remember { context.appGraph.harukaPreferences.relatedManga },
        title = stringResource(HR.strings.pref_related_titles),
        subtitle = stringResource(HR.strings.pref_related_titles_summary),
    )
}
