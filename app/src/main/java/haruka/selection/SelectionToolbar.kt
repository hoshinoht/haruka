package haruka.selection

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults.rememberTooltipPositionProvider
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.unit.dp
import haruka.i18n.HR
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirroredrounded.ArrowForward
import mihon.icons.materialsymbols.rounded.Deselect
import mihon.icons.materialsymbols.rounded.SelectAll
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Bottom floating toolbar for a selectable list: selected count, select all, select none and the screen's
 * primary action. Shown only while [selectedCount] is non-zero; place it in the Scaffold's FAB slot (centred)
 * so the list gets bottom padding for it and it sits above the navigation bar.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SelectionToolbar(
    selectedCount: Int,
    onSelectAll: () -> Unit,
    onSelectNone: () -> Unit,
    primaryLabel: String,
    onPrimary: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Keep showing the last count while the toolbar animates out instead of flashing "0".
    var shownCount by remember { mutableIntStateOf(selectedCount) }
    if (selectedCount > 0) shownCount = selectedCount

    AnimatedVisibility(
        visible = selectedCount > 0,
        modifier = modifier,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
    ) {
        HorizontalFloatingToolbar(
            expanded = true,
            colors = FloatingToolbarDefaults.vibrantFloatingToolbarColors(),
            // The Scaffold's FAB slot passes loose height constraints; pin the M3E toolbar height.
            modifier = Modifier.height(64.dp),
        ) {
            val countDescription = pluralStringResource(HR.plurals.selection_count, shownCount, shownCount)
            Text(
                text = shownCount.toString(),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .align(Alignment.CenterVertically)
                    .padding(horizontal = 12.dp)
                    .clearAndSetSemantics {
                        contentDescription = countDescription
                        liveRegion = LiveRegionMode.Polite
                    },
            )
            ToolbarIconButton(
                label = stringResource(MR.strings.migrationConfigScreen_selectAllLabel),
                icon = MaterialSymbols.Rounded.SelectAll,
                onClick = onSelectAll,
            )
            ToolbarIconButton(
                label = stringResource(MR.strings.migrationConfigScreen_selectNoneLabel),
                icon = MaterialSymbols.Rounded.Deselect,
                onClick = onSelectNone,
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = onPrimary,
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
            ) {
                Icon(
                    imageVector = MaterialSymbols.AutoMirroredRounded.ArrowForward,
                    contentDescription = null,
                    modifier = Modifier.size(ButtonDefaults.IconSize),
                )
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(text = primaryLabel, maxLines = 1)
            }
        }
    }
}

/** Icon button with a long-press tooltip, like [eu.kanade.presentation.components.AppBarActions]. */
@Composable
private fun ToolbarIconButton(label: String, icon: ImageVector, onClick: () -> Unit) {
    TooltipBox(
        positionProvider = rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
        focusable = false,
    ) {
        IconButton(onClick = onClick) {
            Icon(imageVector = icon, contentDescription = label)
        }
    }
}
