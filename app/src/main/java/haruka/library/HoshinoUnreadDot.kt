package haruka.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.kanade.domain.ui.model.AppTheme
import haruka.theme.LocalHarukaAppTheme

/**
 * Hoshino's library unread badge: the round coral count from the Updates conversation view, ringed in the theme's
 * surface so it separates from any cover (upstream's pink badge measured 1.1:1 on pink covers). Draws it and returns
 * true under Hoshino; returns false for every other theme so upstream's badge draws instead.
 */
@Composable
fun hoshinoUnreadDot(count: Long): Boolean {
    if (LocalHarukaAppTheme.current != AppTheme.HOSHINO) return false
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .heightIn(min = 20.dp)
            .widthIn(min = 20.dp)
            .clip(CircleShape)
            .background(colors.error)
            .border(1.5.dp, colors.surface, CircleShape)
            .padding(horizontal = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = colors.onError,
        )
    }
    return true
}
