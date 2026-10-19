package haruka.release

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import eu.kanade.presentation.more.settings.widget.SwitchPreferenceWidget
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import eu.kanade.tachiyomi.BuildConfig
import haruka.di.harukaGraph
import haruka.i18n.HR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

@Composable
fun BetaUpdatesSwitch() {
    val context = LocalContext.current
    val preference = remember { context.harukaGraph.harukaPreferences.betaUpdates }
    val enabled by preference.collectAsState()
    SwitchPreferenceWidget(
        title = stringResource(HR.strings.pref_beta_updates),
        subtitle = stringResource(HR.strings.pref_beta_updates_summary),
        checked = enabled,
        onCheckedChanged = preference::set,
    )
}

@Composable
fun MihonBaseVersion() {
    TextPreferenceWidget(
        title = stringResource(HR.strings.about_mihon_base),
        subtitle = BuildConfig.MIHON_VERSION,
    )
}
