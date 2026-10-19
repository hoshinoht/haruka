package haruka.library.shelves

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Re-tapping the Library tab. Upstream opens the settings sheet; with shelves on and the library scrolled away from
 * the top, the shelves library scrolls back up instead. `LibraryTab.onReselect` asks [consume] first.
 */
object LibraryReselect {
    private var handler: (suspend () -> Boolean)? = null

    /** Whether the shown library handled the reselect; false lets upstream open the settings sheet. */
    suspend fun consume(): Boolean = withContext(Dispatchers.Main.immediate) { handler?.invoke() ?: false }

    /** Registers [onReselect] while the calling composable is shown. */
    @Composable
    internal fun Register(onReselect: suspend () -> Boolean) {
        val current = rememberUpdatedState(onReselect)
        DisposableEffect(Unit) {
            val registered: suspend () -> Boolean = { current.value() }
            handler = registered
            onDispose { if (handler === registered) handler = null }
        }
    }
}
