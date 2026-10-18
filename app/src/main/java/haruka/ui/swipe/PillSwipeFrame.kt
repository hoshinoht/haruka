package haruka.ui.swipe

import kotlin.math.abs

/**
 * What one frame of a pill swipe looks like, in pixels.
 *
 * @property progress Swipe distance as a fraction of the threshold, clamped to `0..1`.
 * @property pillWidth Width of the action pill: the gap the row has uncovered, minus a margin on each side.
 * @property rowCorner Corner radius of the sliding row; reaches its maximum at the threshold.
 * @property armed Whether releasing now fires the action. Strictly past the threshold, matching
 * `me.saket.swipe.SwipeableActionsState.hasCrossedSwipeThreshold`.
 */
internal data class PillSwipeFrame(
    val progress: Float,
    val pillWidth: Float,
    val rowCorner: Float,
    val armed: Boolean,
)

internal fun pillSwipeFrame(
    offset: Float,
    threshold: Float,
    pillMargin: Float,
    maxRowCorner: Float,
): PillSwipeFrame {
    val distance = abs(offset)
    val progress = if (threshold > 0f) (distance / threshold).coerceIn(0f, 1f) else 1f
    return PillSwipeFrame(
        progress = progress,
        pillWidth = (distance - 2 * pillMargin).coerceAtLeast(0f),
        rowCorner = maxRowCorner * progress,
        armed = distance > threshold,
    )
}
