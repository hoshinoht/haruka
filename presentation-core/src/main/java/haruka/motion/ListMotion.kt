package haruka.motion

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

/** REDUCED fades: long enough to read as a state change, short enough not to read as motion. */
private const val REDUCED_FADE_MILLIS = 150

// Tween and snap specs hold no value-typed state, so one instance serves every animated type.
private val ReducedFade = tween<Any?>(REDUCED_FADE_MILLIS)
private val Instant = snap<Any?>()

@Suppress("UNCHECKED_CAST")
private fun <T> reducedFade() = ReducedFade as FiniteAnimationSpec<T>

@Suppress("UNCHECKED_CAST")
private fun <T> instant() = Instant as FiniteAnimationSpec<T>

/**
 * Profile-aware replacement for `Modifier.animateItem()` on a lazy list item, as a plain function so upstream files
 * hook it fully qualified: `modifier = haruka.motion.listItemMotion(this)` or `.then(haruka.motion.listItemMotion(this))`
 * in place of `.animateItem()`. Pass the item's [scope] (`this` in `item {}`).
 *
 * STANDARD makes upstream's exact call: `animateItem()`, or `animateItem(fadeInSpec = null, fadeOutSpec = null)` when
 * [fade] is false. With [animateInStandard] false the item stays static in STANDARD, for lists Mihon does not animate.
 * EXPRESSIVE uses the theme's M3 Expressive springs; REDUCED drops placement motion and keeps only short fades.
 */
@Composable
fun listItemMotion(
    scope: LazyItemScope,
    fade: Boolean = true,
    animateInStandard: Boolean = true,
): Modifier = with(scope) {
    when (LocalAnimationProfile.current) {
        AnimationProfile.STANDARD -> when {
            !animateInStandard -> Modifier
            fade -> Modifier.animateItem()
            else -> Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null)
        }
        AnimationProfile.EXPRESSIVE -> {
            val motion = MaterialTheme.motionScheme
            Modifier.animateItem(
                fadeInSpec = if (fade) motion.defaultEffectsSpec<Float>() else null,
                placementSpec = motion.defaultSpatialSpec<IntOffset>(),
                fadeOutSpec = if (fade) motion.fastEffectsSpec<Float>() else null,
            )
        }
        AnimationProfile.REDUCED -> Modifier.animateItem(
            fadeInSpec = if (fade) reducedFade<Float>() else null,
            placementSpec = null,
            fadeOutSpec = if (fade) reducedFade<Float>() else null,
        )
    }
}

/**
 * Spec for a position/size/rotation value inside a list (expand heights, chevron flips). STANDARD returns [standard];
 * EXPRESSIVE the theme's default spatial spring; REDUCED jumps straight to the target.
 */
@Composable
fun <T> listSpatialSpec(standard: FiniteAnimationSpec<T>): FiniteAnimationSpec<T> =
    when (LocalAnimationProfile.current) {
        AnimationProfile.STANDARD -> standard
        AnimationProfile.EXPRESSIVE -> MaterialTheme.motionScheme.defaultSpatialSpec()
        AnimationProfile.REDUCED -> instant()
    }

/**
 * Spec for a colour/alpha value inside a list (content swaps, state tints). STANDARD returns [standard];
 * EXPRESSIVE the theme's default effects spring; REDUCED a short fade.
 */
@Composable
fun <T> listEffectsSpec(standard: FiniteAnimationSpec<T>): FiniteAnimationSpec<T> =
    when (LocalAnimationProfile.current) {
        AnimationProfile.STANDARD -> standard
        AnimationProfile.EXPRESSIVE -> MaterialTheme.motionScheme.defaultEffectsSpec()
        AnimationProfile.REDUCED -> reducedFade()
    }

/**
 * Profile-aware replacement for `Modifier.animateContentSize()`, hooked as `.then(haruka.motion.contentSizeMotion())`.
 * STANDARD makes upstream's call: with [standardSpec] when given, else the library default. EXPRESSIVE springs with
 * the theme's default spatial spec; REDUCED resizes instantly.
 */
@Composable
fun contentSizeMotion(standardSpec: FiniteAnimationSpec<IntSize>? = null): Modifier =
    when (LocalAnimationProfile.current) {
        AnimationProfile.STANDARD -> if (standardSpec != null) {
            Modifier.animateContentSize(animationSpec = standardSpec)
        } else {
            Modifier.animateContentSize()
        }
        AnimationProfile.EXPRESSIVE -> Modifier.animateContentSize(
            animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
        )
        AnimationProfile.REDUCED -> Modifier
    }

/** Rows revealed by [ExpandableRows]. */
@Stable
interface RevealScope {
    /** Marks the [index]-th revealed row so EXPRESSIVE can cascade it in behind the rows above; no-op otherwise. */
    fun Modifier.revealedRow(index: Int): Modifier
}

private object PlainReveal : RevealScope {
    override fun Modifier.revealedRow(index: Int): Modifier = this
}

// Rows past the last step share its delay, so long groups don't wait on a cascade.
private const val STAGGER_STEPS = 5
private const val STAGGER_STEP_MILLIS = 35
private const val STAGGER_FADE_MILLIS = 150
private val StaggerFades = List(STAGGER_STEPS) {
    tween<Float>(
        STAGGER_FADE_MILLIS,
        delayMillis =
        it * STAGGER_STEP_MILLIS,
    )
}

private class StaggeredReveal(
    private val visibility: AnimatedVisibilityScope,
    private val slide: FiniteAnimationSpec<IntOffset>,
) : RevealScope {
    override fun Modifier.revealedRow(index: Int): Modifier = with(visibility) {
        this@revealedRow.animateEnterExit(
            enter = fadeIn(StaggerFades[index.coerceIn(0, STAGGER_STEPS - 1)]) +
                slideInVertically(slide) { -it / 4 },
            // The container's shrink and fade already carry the exit.
            exit = ExitTransition.None,
        )
    }
}

/**
 * Rows shown below a group head while [expanded], e.g. the hidden chapters of a collapsed group.
 *
 * STANDARD shows and hides them instantly, emitting [content] straight into the caller's layout. EXPRESSIVE springs
 * the region open with the theme's spatial spec and cascades rows marked with [RevealScope.revealedRow]; REDUCED
 * only fades the rows in and out.
 */
@Composable
fun ExpandableRows(expanded: Boolean, content: @Composable RevealScope.() -> Unit) {
    when (LocalAnimationProfile.current) {
        AnimationProfile.STANDARD -> if (expanded) PlainReveal.content()
        AnimationProfile.EXPRESSIVE -> {
            val motion = MaterialTheme.motionScheme
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(motion.defaultSpatialSpec(), expandFrom = Alignment.Top) +
                    fadeIn(motion.defaultEffectsSpec()),
                exit = shrinkVertically(motion.fastSpatialSpec(), shrinkTowards = Alignment.Top) +
                    fadeOut(motion.fastEffectsSpec()),
            ) {
                val slide = motion.defaultSpatialSpec<IntOffset>()
                val reveal = remember(this, slide) { StaggeredReveal(this, slide) }
                Column(Modifier.fillMaxWidth()) { reveal.content() }
            }
        }
        AnimationProfile.REDUCED -> AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(reducedFade()),
            exit = fadeOut(reducedFade()),
        ) {
            Column(Modifier.fillMaxWidth()) { PlainReveal.content() }
        }
    }
}
