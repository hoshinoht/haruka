package haruka.library.shelves

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import eu.kanade.presentation.more.settings.Preference
import haruka.di.harukaGraph
import haruka.i18n.HR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

@Composable
fun rememberLibraryShelvesEnabled(): Boolean {
    val context = LocalContext.current
    val preference = remember { context.harukaGraph.harukaPreferences.libraryShelves }
    val enabled by preference.collectAsState()
    return enabled
}

@Composable
fun libraryShelvesPreference(): Preference.PreferenceItem.SwitchPreference {
    val context = LocalContext.current
    return Preference.PreferenceItem.SwitchPreference(
        preference = remember { context.harukaGraph.harukaPreferences.libraryShelves },
        title = stringResource(HR.strings.pref_library_shelves),
        subtitle = stringResource(HR.strings.pref_library_shelves_summary),
    )
}
