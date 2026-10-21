package haruka.library

import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.domain.ui.model.AppTheme
import haruka.theme.LocalHarukaAppTheme
import tachiyomi.presentation.core.components.Badge

/**
 * Hoshino's library unread count: a coral segment in place of upstream's pink one, so it reads
 * as the Updates conversation view's unread colour. It stays a segment of the cover's badge group, next to the aqua
 * downloaded count; [hoshinoBadgeGroupRing] rings the whole group so it separates from pink covers (upstream's pink
 * badge measured 1.1:1 there). Draws it and returns true under Hoshino; false for every other theme.
 */
@Composable
fun hoshinoUnreadDot(count: Long): Boolean {
    if (LocalHarukaAppTheme.current != AppTheme.HOSHINO) return false
    val colors = MaterialTheme.colorScheme
    Badge(text = "$count", color = colors.error, textColor = colors.onError)
    return true
}

/** A 1.5 dp ring in the theme's surface around a cover's badge group, under Hoshino only. */
@Composable
fun hoshinoBadgeGroupRing(): Modifier {
    if (LocalHarukaAppTheme.current != AppTheme.HOSHINO) return Modifier
    return Modifier.border(1.5.dp, MaterialTheme.colorScheme.surface, MaterialTheme.shapes.extraSmall)
}
