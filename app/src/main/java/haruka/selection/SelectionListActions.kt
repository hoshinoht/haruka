package haruka.selection

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import haruka.i18n.HR
import kotlinx.coroutines.launch
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.ArrowDownward
import mihon.icons.materialsymbols.rounded.ArrowUpward
import mihon.icons.materialsymbols.rounded.Deselect
import mihon.icons.materialsymbols.rounded.SelectAll
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/** Select all / select none / jump to top / jump to bottom for a selectable list. */
@Composable
fun SelectionListActions(
    listState: LazyListState,
    itemCount: Int,
    hasSelection: Boolean,
    onSelectAll: () -> Unit,
    onSelectNone: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    AppBarActions(
        listOf(
            AppBar.Action(
                title = stringResource(MR.strings.migrationConfigScreen_selectAllLabel),
                icon = MaterialSymbols.Rounded.SelectAll,
                onClick = onSelectAll,
            ),
            AppBar.Action(
                title = stringResource(MR.strings.migrationConfigScreen_selectNoneLabel),
                icon = MaterialSymbols.Rounded.Deselect,
                onClick = onSelectNone,
                enabled = hasSelection,
            ),
            AppBar.Action(
                title = stringResource(HR.strings.action_jump_to_top),
                icon = MaterialSymbols.Rounded.ArrowUpward,
                onClick = { scope.launch { listState.scrollToItem(0) } },
            ),
            AppBar.Action(
                title = stringResource(HR.strings.action_jump_to_bottom),
                icon = MaterialSymbols.Rounded.ArrowDownward,
                onClick = { scope.launch { listState.scrollToItem((itemCount - 1).coerceAtLeast(0)) } },
            ),
        ),
    )
}
