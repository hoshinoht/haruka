package haruka.library.shelves

import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState

/**
 * The Library toolbar's scroll behaviour. With shelves on, it slides away while scrolling down the grid and comes
 * back on the first scroll up; otherwise upstream's pinned bar. [keepVisible] (selection, search) shows it again
 * and holds it, so the selection and search toolbars are never hidden.
 */
@Composable
fun rememberLibraryTopBarScrollBehavior(shelvesEnabled: Boolean, keepVisible: Boolean): TopAppBarScrollBehavior {
    val pinned = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
    val collapsingState = rememberTopAppBarState()
    val hold by rememberUpdatedState(!shelvesEnabled || keepVisible)
    val collapsing = TopAppBarDefaults.enterAlwaysScrollBehavior(collapsingState, canScroll = { !hold })
    LaunchedEffect(hold) { if (hold) collapsingState.heightOffset = 0f }
    return if (shelvesEnabled) collapsing else pinned
}
