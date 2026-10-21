package haruka.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationItemColors
import androidx.compose.material3.ShortNavigationBarItemDefaults
import androidx.compose.runtime.Composable
import eu.kanade.domain.ui.model.AppTheme

/**
 * Bottom bar and rail item colours. Under Hoshino the selected tab sits in a solid pink (`primary`) pill with an
 * `onPrimary` icon: the default `secondaryContainer` pill measured 1.7:1 against the bar and read as unselected.
 * Null for every other theme, which keeps upstream's colours.
 */
@Composable
fun hoshinoNavigationItemColors(): NavigationItemColors? {
    if (LocalHarukaAppTheme.current != AppTheme.HOSHINO) return null
    val colors = MaterialTheme.colorScheme
    return ShortNavigationBarItemDefaults.colors(
        selectedIndicatorColor = colors.primary,
        selectedIconColor = colors.onPrimary,
        selectedTextColorTopIconPosition = colors.primary,
        selectedTextColorStartIconPosition = colors.primary,
    )
}
