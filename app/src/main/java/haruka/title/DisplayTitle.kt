package haruka.title

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import eu.kanade.presentation.more.settings.Preference
import haruka.di.harukaGraph
import haruka.i18n.HR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

/** The title to draw: [compactTitle] when the setting is on. Display only; never store the result. */
@Composable
fun rememberDisplayTitle(title: String): String {
    val context = LocalContext.current
    val preference = remember { context.harukaGraph.harukaPreferences.compactTitles }
    val enabled by preference.collectAsState()
    return remember(title, enabled) { if (enabled) compactTitle(title) else title }
}

@Composable
fun compactTitlesPreference(): Preference.PreferenceItem.SwitchPreference {
    val context = LocalContext.current
    return Preference.PreferenceItem.SwitchPreference(
        preference = remember { context.harukaGraph.harukaPreferences.compactTitles },
        title = stringResource(HR.strings.pref_compact_titles),
        subtitle = stringResource(HR.strings.pref_compact_titles_summary),
    )
}
