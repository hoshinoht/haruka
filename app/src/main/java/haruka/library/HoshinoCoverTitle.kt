package haruka.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.domain.ui.model.AppTheme
import haruka.theme.LocalHarukaAppTheme
import haruka.title.rememberDisplayTitle

/**
 * Draws Hoshino's compact-grid title overlay into [box] in place of upstream's and returns true; returns false
 * without drawing for every other theme so upstream's overlay runs unchanged. [continueReading] is upstream's resume
 * button, placed at the end of the title row as upstream does. [box] is a parameter, not a receiver, so the upstream
 * hook can call this fully qualified.
 *
 * The title sits on a plate fading into the theme's `surfaceContainerLowest` (navy in dark, white in light) over the
 * bottom 52% of the cover, in `onSurface` with no shadow: readable on pale and busy covers alike (legibility
 * proposal A, measured at 10.3:1 or better in dark, 9.1:1 in light). Upstream's 33% fade measured 1.7:1 on pale covers.
 */
@Composable
internal fun hoshinoCoverTitle(
    box: BoxScope,
    title: String,
    continueReading: (@Composable () -> Unit)?,
): Boolean {
    if (LocalHarukaAppTheme.current != AppTheme.HOSHINO) return false
    with(box) { CoverTitlePlate(title, continueReading) }
    return true
}

@Composable
private fun BoxScope.CoverTitlePlate(title: String, continueReading: (@Composable () -> Unit)?) {
    val colors = MaterialTheme.colorScheme
    val plate = colors.surfaceContainerLowest
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(bottomStart = 4.dp, bottomEnd = 4.dp))
            .background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.32f to plate.copy(alpha = 0.55f),
                    0.58f to plate.copy(alpha = 0.86f),
                    1f to plate.copy(alpha = 0.94f),
                ),
            )
            .fillMaxHeight(0.52f)
            .fillMaxWidth()
            .align(Alignment.BottomCenter),
    )
    Row(
        modifier = Modifier.align(Alignment.BottomStart),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            text = rememberDisplayTitle(title),
            modifier = Modifier
                .weight(1f)
                .padding(8.dp),
            // Explicit colour: a selected grid item swaps LocalContentColor to onSecondary. Bold, like Hoshino's other
            // titles: only Zen Maru Gothic's Bold ships.
            style = MaterialTheme.typography.titleSmall.copy(
                color = colors.onSurface,
                fontSize = 12.sp,
                lineHeight = 18.sp,
            ),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        continueReading?.invoke()
    }
}
