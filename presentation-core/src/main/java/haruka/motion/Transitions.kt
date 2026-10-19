@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package haruka.motion

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/*
 * Screen transitions and overlays (sheets, menus, app bar swaps). Every helper hands STANDARD back
 * exactly what upstream passed or did, EXPRESSIVE the theme's M3E springs, REDUCED a short fade or nothing.
 */

/** REDUCED fade-in, and the time a closing overlay holds still while it fades out. */
private const val REDUCED_FADE_MILLIS = 150

/** REDUCED fade-out of the outgoing side of a crossfade, short so two screens never read as layered. */
private const val REDUCED_FADE_OUT_MILLIS = 75

/** M3 fade-through start scale for incoming content. */
private const val FADE_THROUGH_INITIAL_SCALE = 0.92f

private fun reducedCrossfade(): ContentTransform =
    fadeIn(tween(REDUCED_FADE_MILLIS)) togetherWith fadeOut(tween(REDUCED_FADE_OUT_MILLIS))

/**
 * Horizontal shared-axis screen transition, hooked where upstream used `materialSharedAxisX`.
 * [standard] gets the direction from [forward]; EXPRESSIVE slides with the theme's spatial spring and fades with
 * its effects springs; REDUCED crossfades.
 */
@Composable
fun <S> sharedAxisXTransition(
    slideDistance: Int,
    forward: AnimatedContentTransitionScope<S>.() -> Boolean,
    standard: AnimatedContentTransitionScope<S>.(forward: Boolean) -> ContentTransform,
): AnimatedContentTransitionScope<S>.() -> ContentTransform {
    val profile = LocalAnimationProfile.current
    if (profile == AnimationProfile.STANDARD) return { standard(forward()) }
    if (profile == AnimationProfile.REDUCED) return { reducedCrossfade() }
    val motionScheme = MaterialTheme.motionScheme
    return { expressiveSharedAxisX(motionScheme, if (forward()) slideDistance else -slideDistance) }
}

private fun expressiveSharedAxisX(motionScheme: MotionScheme, offset: Int): ContentTransform {
    val enter = slideInHorizontally(motionScheme.defaultSpatialSpec()) { offset } +
        fadeIn(motionScheme.defaultEffectsSpec())
    val exit = slideOutHorizontally(motionScheme.defaultSpatialSpec()) { -offset } +
        fadeOut(motionScheme.fastEffectsSpec())
    return enter togetherWith exit
}

/**
 * Fade-through between top-level destinations (home tabs). EXPRESSIVE adds M3's fade-through scale on a spatial
 * spring, which upstream flattens to 1; REDUCED crossfades.
 */
@Composable
fun <S> fadeThroughTransition(
    standard: AnimatedContentTransitionScope<S>.() -> ContentTransform,
): AnimatedContentTransitionScope<S>.() -> ContentTransform {
    val profile = LocalAnimationProfile.current
    if (profile == AnimationProfile.STANDARD) return standard
    if (profile == AnimationProfile.REDUCED) return { reducedCrossfade() }
    val motionScheme = MaterialTheme.motionScheme
    return {
        val enter = fadeIn(motionScheme.defaultEffectsSpec()) +
            scaleIn(motionScheme.defaultSpatialSpec(), initialScale = FADE_THROUGH_INITIAL_SCALE)
        enter togetherWith fadeOut(motionScheme.fastEffectsSpec())
    }
}

/**
 * Screen changes inside a sheet. EXPRESSIVE fades with effects springs and resizes the sheet on the spatial spring;
 * REDUCED crossfades and resizes instantly.
 */
@Composable
fun <S> sheetContentTransition(
    standard: AnimatedContentTransitionScope<S>.() -> ContentTransform,
): AnimatedContentTransitionScope<S>.() -> ContentTransform {
    val profile = LocalAnimationProfile.current
    if (profile == AnimationProfile.STANDARD) return standard
    if (profile == AnimationProfile.REDUCED) return { reducedCrossfade() using SizeTransform { _, _ -> snap() } }
    val motionScheme = MaterialTheme.motionScheme
    return {
        fadeIn(motionScheme.defaultEffectsSpec()) togetherWith fadeOut(motionScheme.fastEffectsSpec()) using
            SizeTransform { _, _ -> motionScheme.defaultSpatialSpec() }
    }
}

/**
 * Settle/fling spec of the adaptive sheet: the drag settle on phones, the fade on tablets. EXPRESSIVE uses the
 * spatial spring (effects spring for the tablet fade); REDUCED a short tween without overshoot.
 */
@Composable
fun sheetAnimationSpec(standard: AnimationSpec<Float>, isTabletUi: Boolean): AnimationSpec<Float> =
    when (LocalAnimationProfile.current) {
        AnimationProfile.STANDARD -> standard
        AnimationProfile.EXPRESSIVE -> if (isTabletUi) {
            MaterialTheme.motionScheme.defaultEffectsSpec()
        } else {
            MaterialTheme.motionScheme.defaultSpatialSpec()
        }
        AnimationProfile.REDUCED -> tween(REDUCED_FADE_MILLIS)
    }

/**
 * Spec for the sheet sliding open, or null for upstream's `animateTo` default. EXPRESSIVE follows M3's
 * ModalBottomSheet (default spatial spring); REDUCED appears in place and [sheetFadeModifier] fades it in.
 */
@Composable
fun sheetOpenSpec(): AnimationSpec<Float>? = when (LocalAnimationProfile.current) {
    AnimationProfile.STANDARD -> null
    AnimationProfile.EXPRESSIVE -> MaterialTheme.motionScheme.defaultSpatialSpec()
    AnimationProfile.REDUCED -> snap()
}

/**
 * Spec for an implicit (scrim tap, back) sheet dismissal, or null for upstream's `animateTo` default. EXPRESSIVE
 * follows M3's ModalBottomSheet hide (fast effects spring); REDUCED holds the sheet in place while
 * [sheetFadeModifier] fades it out, then drops it.
 */
@Composable
fun sheetDismissSpec(): AnimationSpec<Float>? = when (LocalAnimationProfile.current) {
    AnimationProfile.STANDARD -> null
    AnimationProfile.EXPRESSIVE -> MaterialTheme.motionScheme.fastEffectsSpec()
    // Held one extra frame pair past the fade so the last faded frames draw before the sheet is dropped.
    AnimationProfile.REDUCED -> snap(delayMillis = REDUCED_FADE_MILLIS + 32)
}

/** `state.animateTo(target)`, with [spec] when a profile supplies one, else exactly upstream's default call. */
suspend fun <T> animateSheetTo(state: AnchoredDraggableState<T>, target: T, spec: AnimationSpec<Float>?) {
    if (spec == null) state.animateTo(target) else state.animateTo(target, spec)
}

/** REDUCED fades the phone sheet in and out (it no longer slides); no-op otherwise. */
@Composable
fun sheetFadeModifier(visible: Boolean): Modifier {
    if (LocalAnimationProfile.current != AnimationProfile.REDUCED) return Modifier
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(REDUCED_FADE_MILLIS),
        label = "sheetFade",
    )
    return Modifier.graphicsLayer { this.alpha = alpha }
}

/**
 * Pager scroll for a tab click. EXPRESSIVE scrolls on the spatial spring; REDUCED jumps to the page.
 */
@Composable
fun pagerTabScroll(): suspend (PagerState, Int) -> Unit = when (LocalAnimationProfile.current) {
    AnimationProfile.STANDARD -> StandardTabScroll
    AnimationProfile.EXPRESSIVE -> {
        val motionScheme = MaterialTheme.motionScheme
        remember(motionScheme) {
            val spec = motionScheme.defaultSpatialSpec<Float>()
            val scroll: suspend (PagerState, Int) -> Unit = { state, page ->
                state.animateScrollToPage(page, animationSpec = spec)
            }
            scroll
        }
    }
    AnimationProfile.REDUCED -> ReducedTabScroll
}

private val StandardTabScroll: suspend (PagerState, Int) -> Unit = { state, page -> state.animateScrollToPage(page) }
private val ReducedTabScroll: suspend (PagerState, Int) -> Unit = { state, page -> state.scrollToPage(page) }

/**
 * Wraps a dropdown menu. M3's menu already scales and fades on the theme's fast spatial/effects specs, so STANDARD
 * and EXPRESSIVE (both on the expressive scheme) pass through. REDUCED swaps in a scheme whose spatial specs never
 * visibly move: the scale jumps to full size on open and holds until the fade-out ends on close.
 */
@Composable
fun MenuMotion(expanded: Boolean, content: @Composable () -> Unit) {
    if (LocalAnimationProfile.current != AnimationProfile.REDUCED) {
        content()
        return
    }
    MaterialExpressiveTheme(
        motionScheme = if (expanded) ReducedOverlayOpening else ReducedOverlayClosing,
        content = content,
    )
}

/** Fades on [REDUCED_FADE_MILLIS] tweens; spatial changes snap after [spatialDelayMillis]. */
private class ReducedOverlayMotionScheme(private val spatialDelayMillis: Int) : MotionScheme {
    override fun <T> defaultSpatialSpec(): FiniteAnimationSpec<T> = snap(spatialDelayMillis)
    override fun <T> fastSpatialSpec(): FiniteAnimationSpec<T> = snap(spatialDelayMillis)
    override fun <T> slowSpatialSpec(): FiniteAnimationSpec<T> = snap(spatialDelayMillis)
    override fun <T> defaultEffectsSpec(): FiniteAnimationSpec<T> = tween(REDUCED_FADE_MILLIS)
    override fun <T> fastEffectsSpec(): FiniteAnimationSpec<T> = tween(REDUCED_FADE_MILLIS)
    override fun <T> slowEffectsSpec(): FiniteAnimationSpec<T> = tween(REDUCED_FADE_MILLIS)
}

private val ReducedOverlayOpening: MotionScheme = ReducedOverlayMotionScheme(spatialDelayMillis = 0)
private val ReducedOverlayClosing: MotionScheme = ReducedOverlayMotionScheme(spatialDelayMillis = REDUCED_FADE_MILLIS)

/**
 * App bar title content that changes with [targetState] (action mode, search open). Upstream swaps instantly, as do
 * STANDARD and REDUCED. EXPRESSIVE runs a vertical shared-axis swap on the theme's springs whenever [contentKey]
 * changes; within one key the content just updates. [content] receives each side's own state, so the outgoing
 * side keeps showing what it showed.
 */
@Composable
fun <S> appBarSwap(
    targetState: S,
    contentKey: (S) -> Any?,
    content: @Composable (S) -> Unit,
): @Composable () -> Unit {
    if (LocalAnimationProfile.current != AnimationProfile.EXPRESSIVE) return { content(targetState) }
    val motionScheme = MaterialTheme.motionScheme
    return {
        AnimatedContent(
            targetState = targetState,
            transitionSpec = { appBarSwapTransform(motionScheme) },
            contentKey = contentKey,
            label = "appBarSwap",
        ) { content(it) }
    }
}

/** [appBarSwap] for the actions row; EXPRESSIVE lays each side out in its own centred row. */
@Composable
fun <S> appBarActionsSwap(
    targetState: S,
    contentKey: (S) -> Any?,
    content: @Composable RowScope.(S) -> Unit,
): @Composable RowScope.() -> Unit {
    if (LocalAnimationProfile.current != AnimationProfile.EXPRESSIVE) return { content(targetState) }
    val motionScheme = MaterialTheme.motionScheme
    return {
        AnimatedContent(
            targetState = targetState,
            transitionSpec = { appBarSwapTransform(motionScheme) },
            contentKey = contentKey,
            label = "appBarActionsSwap",
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) { content(it) }
        }
    }
}

private fun AnimatedContentTransitionScope<*>.appBarSwapTransform(motionScheme: MotionScheme): ContentTransform {
    val enter = fadeIn(motionScheme.defaultEffectsSpec()) +
        slideInVertically(motionScheme.fastSpatialSpec()) { it / 2 }
    val exit = fadeOut(motionScheme.fastEffectsSpec()) +
        slideOutVertically(motionScheme.fastSpatialSpec()) { -it / 2 }
    return enter togetherWith exit using SizeTransform { _, _ -> motionScheme.fastSpatialSpec() }
}
