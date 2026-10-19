package haruka.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import eu.kanade.domain.ui.model.AppTheme

/** The app theme in effect, provided by [HarukaExpressiveTheme]; null outside it (previews keep upstream looks). */
internal val LocalHarukaAppTheme = staticCompositionLocalOf<AppTheme?> { null }

/**
 * End colour of the compact grid's title fade. Hoshino fades to its Abydos navy instead of black, at upstream's
 * alpha (0xAA), in light and dark alike; every other theme keeps upstream's black.
 */
@Composable
@ReadOnlyComposable
fun coverScrimColor(): Color =
    if (LocalHarukaAppTheme.current == AppTheme.HOSHINO) HoshinoNavyScrim else UpstreamScrim

private val UpstreamScrim = Color(0xAA000000)
private val HoshinoNavyScrim = Color(0xAA111B2B)
