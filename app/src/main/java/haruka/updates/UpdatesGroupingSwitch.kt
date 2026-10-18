package haruka.updates

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import haruka.i18n.HR
import mihon.app.di.appGraph
import tachiyomi.presentation.core.components.SettingsItemsPaddings
import tachiyomi.presentation.core.components.material.DISABLED_ALPHA
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

/** Switch rows for the Updates filter sheet, styled like upstream's excluded-scanlators switch. */
@Composable
fun UpdatesGroupingSwitch() {
    val context = LocalContext.current
    val groupPreference = remember { context.appGraph.harukaPreferences.groupUpdates }
    val conversationsPreference = remember { context.appGraph.harukaPreferences.momotalkUpdates }
    val grouped by groupPreference.collectAsState()
    val conversations by conversationsPreference.collectAsState()

    // Conversations are built from the groups, so grouping shows as on and locked while they are.
    SwitchRow(
        label = stringResource(HR.strings.pref_group_updates),
        checked = grouped || conversations,
        enabled = !conversations,
        onCheckedChange = groupPreference::set,
    )
    SwitchRow(
        label = stringResource(HR.strings.pref_updates_conversations),
        checked = conversations,
        enabled = true,
        onCheckedChange = conversationsPreference::set,
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, enabled: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(horizontal = SettingsItemsPaddings.Horizontal),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else DISABLED_ALPHA),
            style = MaterialTheme.typography.bodyMedium,
        )
        // The row handles toggling so the label and switch read as one control.
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}
