package haruka.selection

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import haruka.i18n.HR
import kotlinx.coroutines.launch
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Jump to top / jump to bottom as app bar overflow entries, keeping the bar free for the title.
 * [itemCount] is the number of lazy items in the list, headers included.
 */
@Composable
fun ListJumpActions(listState: LazyListState, itemCount: Int) {
    val scope = rememberCoroutineScope()
    AppBarActions(
        listOf(
            AppBar.OverflowAction(
                title = stringResource(HR.strings.action_jump_to_top),
                onClick = { scope.launch { listState.scrollToItem(0) } },
            ),
            AppBar.OverflowAction(
                title = stringResource(HR.strings.action_jump_to_bottom),
                onClick = { scope.launch { listState.scrollToItem((itemCount - 1).coerceAtLeast(0)) } },
            ),
        ),
    )
}
