package haruka.library.shelves

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import eu.kanade.presentation.more.settings.Preference
import haruka.di.harukaGraph
import haruka.i18n.HR
import haruka.library.ShelfKind
import haruka.preference.HarukaPreferences
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import tachiyomi.core.common.preference.Preference as PreferenceData

@Composable
fun rememberLibraryShelvesEnabled(): Boolean {
    val context = LocalContext.current
    val preference = remember { context.harukaGraph.harukaPreferences.libraryShelves }
    val enabled by preference.collectAsState()
    return enabled
}

internal fun HarukaPreferences.shelfPreference(kind: ShelfKind): PreferenceData<Boolean> = when (kind) {
    ShelfKind.ContinueReading -> libraryShelfContinueReading
    ShelfKind.NewChapters -> libraryShelfNewChapters
    ShelfKind.NotStarted -> libraryShelfNotStarted
    ShelfKind.NeedsNewSource -> libraryShelfNeedsNewSource
}

/** The shelves the user shows. */
@Composable
internal fun rememberEnabledShelves(): Set<ShelfKind> {
    val preferences = LocalContext.current.harukaGraph.harukaPreferences
    return ShelfKind.entries.filterTo(LinkedHashSet()) { kind ->
        val preference = remember(kind) { preferences.shelfPreference(kind) }
        preference.collectAsState().value
    }
}

/** The main toggle, then one per shelf, shown only while the main toggle is on. */
@Composable
fun libraryShelvesPreferences(): Array<Preference.PreferenceItem.SwitchPreference> {
    val preferences = LocalContext.current.harukaGraph.harukaPreferences
    val main = remember { preferences.libraryShelves }
    val enabled by main.collectAsState()
    val shelves = ShelfKind.entries.map { kind ->
        Preference.PreferenceItem.SwitchPreference(
            preference = remember(kind) { preferences.shelfPreference(kind) },
            title = stringResource(kind.title),
            visible = enabled,
        )
    }
    return arrayOf(
        Preference.PreferenceItem.SwitchPreference(
            preference = main,
            title = stringResource(HR.strings.pref_library_shelves),
            subtitle = stringResource(HR.strings.pref_library_shelves_summary),
        ),
    ) + shelves
}
