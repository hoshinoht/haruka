@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package haruka.motion

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.WavyProgressIndicatorDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.CircularProgressIndicator as M3CircularProgressIndicator

/**
 * Drop-in for an indeterminate M3 `CircularProgressIndicator()` loading a whole screen or list: upstream files swap
 * the androidx import for this one. EXPRESSIVE uses the M3E shape-morphing [LoadingIndicator]; STANDARD keeps
 * Mihon's spinner, and REDUCED keeps it too because a non-morphing spinner is the least motion that still shows
 * the app is working.
 */
@Composable
fun CircularProgressIndicator(modifier: Modifier = Modifier) {
    if (LocalAnimationProfile.current == AnimationProfile.EXPRESSIVE) {
        LoadingIndicator(modifier = modifier)
    } else {
        M3CircularProgressIndicator(modifier = modifier)
    }
}

/**
 * Replaces Mihon's `CombinedCircularProgressIndicator` body outside STANDARD (STANDARD runs upstream's own).
 * EXPRESSIVE: [LoadingIndicator] until progress arrives, then a wavy determinate ring whose travelling wave
 * replaces upstream's constant rotation as the "not stuck" cue. REDUCED: plain rings, no rotation loop and
 * no crossfade.
 */
@Composable
fun ProfileCombinedProgressIndicator(progress: () -> Float, modifier: Modifier = Modifier) {
    val indeterminate = progress() == 0f
    if (LocalAnimationProfile.current == AnimationProfile.REDUCED) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            if (indeterminate) {
                M3CircularProgressIndicator()
            } else {
                M3CircularProgressIndicator(progress = progress)
            }
        }
        return
    }
    val fadeSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    AnimatedContent(
        targetState = indeterminate,
        transitionSpec = { fadeIn(fadeSpec) togetherWith fadeOut(fadeSpec) },
        label = "progressState",
        modifier = modifier,
    ) { isIndeterminate ->
        if (isIndeterminate) {
            LoadingIndicator()
        } else {
            val animatedProgress by animateFloatAsState(
                targetValue = progress(),
                animationSpec = WavyProgressIndicatorDefaults.ProgressAnimationSpec,
                label = "progress",
            )
            CircularWavyProgressIndicator(progress = { animatedProgress })
        }
    }
}

/**
 * Pull-to-refresh indicator: EXPRESSIVE uses the M3E contained [LoadingIndicator] variant, the other
 * profiles keep Mihon's spinner. Colours are passed through so the container matches upstream's.
 */
@Composable
fun ProfilePullToRefreshIndicator(
    state: PullToRefreshState,
    isRefreshing: Boolean,
    containerColor: Color,
    color: Color,
    modifier: Modifier = Modifier,
) {
    if (LocalAnimationProfile.current == AnimationProfile.EXPRESSIVE) {
        PullToRefreshDefaults.LoadingIndicator(
            state = state,
            isRefreshing = isRefreshing,
            modifier = modifier,
            containerColor = containerColor,
            color = color,
        )
    } else {
        PullToRefreshDefaults.Indicator(
            state = state,
            isRefreshing = isRefreshing,
            modifier = modifier,
            containerColor = containerColor,
            color = color,
        )
    }
}

/** Wave height for wavy indicators: flat under REDUCED so the wave stops travelling. */
@Composable
@ReadOnlyComposable
fun wavyAmplitude(amplitude: Float): Float =
    if (LocalAnimationProfile.current == AnimationProfile.REDUCED) 0f else amplitude

/** Progress-dependent wave height for determinate wavy indicators: flat under REDUCED. */
@Composable
@ReadOnlyComposable
fun wavyAmplitude(amplitude: (Float) -> Float): (Float) -> Float =
    if (LocalAnimationProfile.current == AnimationProfile.REDUCED) FlatAmplitude else amplitude

private val FlatAmplitude: (Float) -> Float = { 0f }
