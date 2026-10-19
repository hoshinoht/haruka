package haruka.motion

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import eu.kanade.presentation.more.settings.Preference
import haruka.di.harukaGraph
import haruka.i18n.HR
import tachiyomi.presentation.core.components.HeadingItem
import tachiyomi.presentation.core.components.SettingsItemsPaddings
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

/** Settings › Appearance row choosing the [AnimationProfile] as one connected single-choice button group. */
@Composable
fun animationProfilePreference(): Preference.PreferenceItem.CustomPreference {
    val title = stringResource(HR.strings.pref_animation_profile)
    return Preference.PreferenceItem.CustomPreference(title = title) {
        AnimationProfileButtons(title)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AnimationProfileButtons(title: String) {
    val context = LocalContext.current
    val preference = remember { context.harukaGraph.harukaPreferences.animationProfile }
    val selected by preference.collectAsState()
    val options = listOf(
        AnimationProfile.STANDARD to stringResource(HR.strings.animation_profile_standard),
        AnimationProfile.EXPRESSIVE to stringResource(HR.strings.animation_profile_expressive),
        AnimationProfile.REDUCED to stringResource(HR.strings.animation_profile_reduced),
    )
    val summary = when (selected) {
        AnimationProfile.STANDARD -> HR.strings.animation_profile_standard_summary
        AnimationProfile.EXPRESSIVE -> HR.strings.animation_profile_expressive_summary
        AnimationProfile.REDUCED -> HR.strings.animation_profile_reduced_summary
    }
    Column {
        HeadingItem(title)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = SettingsItemsPaddings.Horizontal)
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
        ) {
            options.forEachIndexed { index, (profile, label) ->
                ToggleButton(
                    checked = selected == profile,
                    onCheckedChange = { preference.set(profile) },
                    shapes = when (index) {
                        0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                        options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                        else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                    },
                    modifier = Modifier
                        .weight(1f)
                        .semantics { role = Role.RadioButton },
                ) {
                    Text(text = label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Text(
            text = stringResource(summary),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(
                start = SettingsItemsPaddings.Horizontal,
                end = SettingsItemsPaddings.Horizontal,
                top = SettingsItemsPaddings.Vertical / 2,
                bottom = SettingsItemsPaddings.Vertical,
            ),
        )
    }
}
