package haruka.ui.swipe

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.data.download.model.Download
import haruka.motion.AnimationProfile
import haruka.motion.LocalAnimationProfile
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import me.saket.swipe.SwipeAction
import me.saket.swipe.SwipeableActionsBox
import me.saket.swipe.SwipeableActionsState
import me.saket.swipe.rememberSwipeableActionsState
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.BookmarkAdd
import mihon.icons.materialsymbols.rounded.BookmarkRemove
import mihon.icons.materialsymbols.rounded.Check
import mihon.icons.materialsymbols.rounded.Delete
import mihon.icons.materialsymbols.rounded.Download
import mihon.icons.materialsymbols.rounded.FileDownloadOff
import mihon.icons.materialsymbols.rounded.RemoveDone
import tachiyomi.domain.library.service.LibraryPreferences.ChapterSwipeAction
import kotlin.math.roundToInt

private val PillMargin = 8.dp

// Fallback when the action slot does not pass the row's height; normally the pill fills the row minus margins.
private val PillHeight = 40.dp
private val PillIconSize = 24.dp
private val MaxRowCorner = 20.dp

// Below the threshold the pill is a rounded square; once armed it morphs into a full stadium.
private val PillCornerIdle = 12.dp

private val PillSpring = spring<Float>(
    dampingRatio = Spring.DampingRatioMediumBouncy,
    stiffness = Spring.StiffnessMedium,
)
private val PillCornerSpring = spring<Dp>(
    dampingRatio = Spring.DampingRatioMediumBouncy,
    stiffness = Spring.StiffnessMedium,
)

/**
 * Chapter-row swipe box. With the Expressive animation profile, the row slides with rounded corners over a
 * growing action pill; otherwise [SwipeableActionsBox] is called with exactly the arguments upstream passes.
 *
 * The chapter parameters drive the pill actions. [modifier], [startActions], [endActions], [swipeThreshold] and
 * [backgroundUntilSwipeThreshold] are upstream's own values; the pill path reuses only [modifier] and
 * [swipeThreshold], so the threshold means the same in both styles.
 */
@Composable
fun ChapterSwipeBox(
    startAction: ChapterSwipeAction,
    endAction: ChapterSwipeAction,
    read: Boolean,
    bookmark: Boolean,
    downloadState: Download.State,
    onChapterSwipe: (ChapterSwipeAction) -> Unit,
    modifier: Modifier,
    startActions: List<SwipeAction>,
    endActions: List<SwipeAction>,
    swipeThreshold: Dp,
    backgroundUntilSwipeThreshold: Color,
    content: @Composable BoxScope.() -> Unit,
) {
    if (LocalAnimationProfile.current != AnimationProfile.EXPRESSIVE) {
        SwipeableActionsBox(
            modifier = modifier,
            startActions = startActions,
            endActions = endActions,
            swipeThreshold = swipeThreshold,
            backgroundUntilSwipeThreshold = backgroundUntilSwipeThreshold,
            content = content,
        )
        return
    }

    val state = rememberSwipeableActionsState()
    val thresholdPx = with(LocalDensity.current) { swipeThreshold.toPx() }
    val start = pillSwipeAction(startAction, read, bookmark, downloadState, state, thresholdPx) {
        onChapterSwipe(startAction)
    }
    val end = pillSwipeAction(endAction, read, bookmark, downloadState, state, thresholdPx) {
        onChapterSwipe(endAction)
    }
    PillSwipeBox(
        state = state,
        thresholdPx = thresholdPx,
        modifier = modifier,
        startActions = listOfNotNull(start),
        endActions = listOfNotNull(end),
        swipeThreshold = swipeThreshold,
        content = content,
    )
}

@Composable
private fun PillSwipeBox(
    state: SwipeableActionsState,
    thresholdPx: Float,
    modifier: Modifier,
    startActions: List<SwipeAction>,
    endActions: List<SwipeAction>,
    swipeThreshold: Dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    // One light tick each time the drag crosses into the armed zone; the spring-back after release never ticks.
    LaunchedEffect(state, thresholdPx, haptics) {
        snapshotFlow { !state.isResettingOnRelease && isArmed(state, thresholdPx) }
            .distinctUntilChanged()
            .filter { it }
            .collect { haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate) }
    }

    val density = LocalDensity.current
    val pillMarginPx = with(density) { PillMargin.toPx() }
    val maxRowCornerPx = with(density) { MaxRowCorner.toPx() }
    val rowColor = MaterialTheme.colorScheme.surfaceContainerHigh

    // The library buzzes with a long-press haptic at the threshold; silence it so only the tick above plays,
    // and hand the row its real haptics back so long-click feedback is unchanged.
    CompositionLocalProvider(LocalHapticFeedback provides NoHapticFeedback) {
        SwipeableActionsBox(
            modifier = modifier,
            state = state,
            startActions = startActions,
            endActions = endActions,
            swipeThreshold = swipeThreshold,
            backgroundUntilSwipeThreshold = Color.Transparent,
        ) {
            CompositionLocalProvider(LocalHapticFeedback provides haptics) {
                Box(
                    modifier = Modifier
                        .graphicsLayer {
                            val frame = pillSwipeFrame(state.offset.value, thresholdPx, pillMarginPx, maxRowCornerPx)
                            clip = frame.rowCorner > 0f
                            shape = RoundedCornerShape(frame.rowCorner)
                        }
                        .drawBehind {
                            val offset = state.offset.value
                            if (offset != 0f) {
                                val frame = pillSwipeFrame(offset, thresholdPx, pillMarginPx, maxRowCornerPx)
                                drawRect(rowColor, alpha = frame.progress)
                            }
                        },
                    content = content,
                )
            }
        }
    }
}

private fun pillSwipeAction(
    action: ChapterSwipeAction,
    read: Boolean,
    bookmark: Boolean,
    downloadState: Download.State,
    state: SwipeableActionsState,
    thresholdPx: Float,
    onSwipe: () -> Unit,
): SwipeAction? {
    // Same icons and undo flags as upstream's getSwipeAction in MangaChapterListItem.
    val (icon, isUndo) = when (action) {
        ChapterSwipeAction.ToggleRead ->
            (if (!read) MaterialSymbols.Rounded.Check else MaterialSymbols.Rounded.RemoveDone) to read
        ChapterSwipeAction.ToggleBookmark ->
            (if (!bookmark) MaterialSymbols.Rounded.BookmarkAdd else MaterialSymbols.Rounded.BookmarkRemove) to
                bookmark
        ChapterSwipeAction.Download -> when (downloadState) {
            Download.State.NOT_DOWNLOADED, Download.State.ERROR -> MaterialSymbols.Rounded.Download
            Download.State.QUEUE, Download.State.DOWNLOADING -> MaterialSymbols.Rounded.FileDownloadOff
            Download.State.DOWNLOADED -> MaterialSymbols.Rounded.Delete
        } to false
        ChapterSwipeAction.Disabled -> return null
    }
    return SwipeAction(
        icon = { SwipePill(icon, state, thresholdPx) },
        // The library paints this full-width behind the row and as its release ripple; the pill replaces both.
        background = Color.Transparent,
        onSwipe = onSwipe,
        isUndo = isUndo,
    )
}

@Composable
private fun SwipePill(
    icon: ImageVector,
    state: SwipeableActionsState,
    thresholdPx: Float,
) {
    // A swipe that fired keeps its armed look while the row springs back.
    val armed by remember(state, thresholdPx) {
        derivedStateOf { state.isResettingOnRelease || isArmed(state, thresholdPx) }
    }
    val colors = MaterialTheme.colorScheme
    val container by animateColorAsState(
        targetValue = if (armed) colors.primary else colors.secondaryContainer,
        label = "pillContainer",
    )
    val iconTint by animateColorAsState(
        targetValue = if (armed) colors.onPrimary else colors.onSecondaryContainer,
        label = "pillIcon",
    )
    // The pill spans the row's height, so its stadium radius follows the measured height.
    var pillHeightPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val corner by animateDpAsState(
        targetValue = if (armed && pillHeightPx > 0) with(density) { (pillHeightPx / 2).toDp() } else PillCornerIdle,
        animationSpec = PillCornerSpring,
        label = "pillCorner",
    )
    val iconScale by animateFloatAsState(
        targetValue = if (armed) 1.15f else 1f,
        animationSpec = PillSpring,
        label = "pillIconScale",
    )
    val pillMarginPx = with(density) { PillMargin.toPx() }

    Box(
        modifier = Modifier
            .padding(PillMargin)
            .layout { measurable, constraints ->
                val frame = pillSwipeFrame(state.offset.value, thresholdPx, pillMarginPx, 0f)
                val height = if (constraints.hasBoundedHeight) constraints.maxHeight else PillHeight.roundToPx()
                pillHeightPx = height
                val placeable = measurable.measure(Constraints.fixed(frame.pillWidth.roundToInt(), height))
                layout(placeable.width, placeable.height) { placeable.place(0, 0) }
            }
            .clip(RoundedCornerShape(corner))
            .background(container),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier
                // Keeps the glyph whole while the pill is narrower than it; the pill's clip trims it instead.
                .requiredSize(PillIconSize)
                .graphicsLayer {
                    alpha = pillSwipeFrame(state.offset.value, thresholdPx, pillMarginPx, 0f).progress
                    scaleX = iconScale
                    scaleY = iconScale
                },
        )
    }
}

private fun isArmed(state: SwipeableActionsState, thresholdPx: Float): Boolean =
    pillSwipeFrame(state.offset.value, thresholdPx, 0f, 0f).armed

private object NoHapticFeedback : HapticFeedback {
    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) = Unit
}
