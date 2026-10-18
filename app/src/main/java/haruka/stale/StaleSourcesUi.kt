package haruka.stale

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import haruka.i18n.HR
import kotlinx.coroutines.launch
import mihon.feature.migration.config.MigrationConfigScreen
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.SwapCalls
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.secondaryItemAlpha

/** Toolbar action sending every library entry of an unavailable source to bulk migration; null if there are none. */
@Composable
fun migrateUnavailableAction(
    hasUnavailable: Boolean,
    favoriteIds: suspend () -> List<Long>,
): AppBar.Action? {
    val navigator = LocalNavigator.currentOrThrow
    val scope = rememberCoroutineScope()
    if (!hasUnavailable) return null
    return AppBar.Action(
        title = stringResource(HR.strings.action_migrate_unavailable),
        icon = MaterialSymbols.Rounded.SwapCalls,
        onClick = {
            scope.launch {
                val ids = favoriteIds()
                if (ids.isNotEmpty()) navigator.push(MigrationConfigScreen(ids))
            }
        },
    )
}

/** Error-coloured label in a source row, matching upstream's "Not installed" label. */
@Composable
fun UnavailableSourceLabel(reason: UnavailableReason?) {
    if (reason == null) return
    Text(
        modifier = Modifier.secondaryItemAlpha(),
        text = stringResource(
            when (reason) {
                UnavailableReason.NotInstalled -> MR.strings.not_installed
                UnavailableReason.NotLoaded -> MR.strings.ext_not_loaded
                UnavailableReason.Obsolete -> MR.strings.ext_obsolete
            },
        ),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
    )
}
