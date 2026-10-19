package haruka.cover

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import eu.kanade.presentation.more.settings.Preference
import haruka.di.harukaGraph
import haruka.i18n.HR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun panoramaCoversPreference(): Preference.PreferenceItem.SwitchPreference {
    val context = LocalContext.current
    return Preference.PreferenceItem.SwitchPreference(
        preference = remember { context.harukaGraph.harukaPreferences.panoramaCovers },
        title = stringResource(HR.strings.pref_panorama_covers),
        subtitle = stringResource(HR.strings.pref_panorama_covers_summary),
    )
}
