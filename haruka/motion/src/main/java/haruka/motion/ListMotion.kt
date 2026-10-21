package haruka.motion

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.animation.animateContentSize as foundationAnimateContentSize

/** REDUCED fades: long enough to read as a state change, short enough not to read as motion. */
private const val REDUCED_FADE_MILLIS = 150

// Tween and snap specs hold no value-typed state, so one instance serves every animated type.
private val ReducedFade = tween<Any?>(REDUCED_FADE_MILLIS)
private val Instant = snap<Any?>()

@Suppress("UNCHECKED_CAST")
private fun <T> reducedFade() = ReducedFade as FiniteAnimationSpec<T>

@Suppress("UNCHECKED_CAST")
private fun <T> instant() = Instant as FiniteAnimationSpec<T>

// What `LazyItemScope.animateItem()` passes for an omitted argument (foundation 1.13). SpringSpec compares by value, so
// an argument equal to these was left at its default by the caller.
private val DefaultItemFade = spring<Float>(stiffness = Spring.StiffnessMediumLow)
private val DefaultItemPlacement = spring(
    stiffness = Spring.StiffnessMediumLow,
    visibilityThreshold = IntOffset.VisibilityThreshold,
)

/**
 * The specs a profile gives `Modifier.animateItem()`. An argument equal to the library default maps to the profile's
 * spec, null stays null (the caller turned that part off) and any other spec passes through (the caller chose it).
 */
@Immutable
class ListItemSpecs internal constructor(
    private val fadeIn: FiniteAnimationSpec<Float>?,
    private val placement: FiniteAnimationSpec<IntOffset>?,
    private val fadeOut: FiniteAnimationSpec<Float>?,
) {
    fun fadeIn(requested: FiniteAnimationSpec<Float>?): FiniteAnimationSpec<Float>? =
        if (requested == DefaultItemFade) fadeIn else requested

    fun placement(requested: FiniteAnimationSpec<IntOffset>?): FiniteAnimationSpec<IntOffset>? =
        if (requested == DefaultItemPlacement) placement else requested

    fun fadeOut(requested: FiniteAnimationSpec<Float>?): FiniteAnimationSpec<Float>? =
        if (requested == DefaultItemFade) fadeOut else requested
}

/**
 * Item specs for [profile], or null for STANDARD, whose items keep exactly what upstream asks for. EXPRESSIVE uses
 * [motionScheme]'s springs; REDUCED drops placement motion and keeps only short fades.
 */
fun listItemSpecs(profile: AnimationProfile, motionScheme: MotionScheme): ListItemSpecs? = when (profile) {
    AnimationProfile.STANDARD -> null
    AnimationProfile.EXPRESSIVE -> ListItemSpecs(
        fadeIn = motionScheme.defaultEffectsSpec(),
        placement = motionScheme.defaultSpatialSpec(),
        fadeOut = motionScheme.fastEffectsSpec(),
    )
    AnimationProfile.REDUCED -> ListItemSpecs(
        fadeIn = reducedFade(),
        placement = null,
        fadeOut = reducedFade(),
    )
}

/**
 * Runs a lazy list's [content] so every `Modifier.animateItem()` inside it follows the animation profile, hooked as
 * `content = haruka.motion.profiledListContent(content)` in the list wrappers. STANDARD returns [content] itself, so
 * the list behaves exactly like upstream.
 */
@Composable
fun profiledListContent(content: LazyListScope.() -> Unit): LazyListScope.() -> Unit {
    val profile = LocalAnimationProfile.current
    if (profile == AnimationProfile.STANDARD) return content
    val motionScheme = MaterialTheme.motionScheme
    val itemScopes = remember(profile, motionScheme) {
        ProfiledItemScopes(listItemSpecs(profile, motionScheme)!!)
    }
    return remember<LazyListScope.() -> Unit>(content, itemScopes) {
        { ProfiledListScope(this, itemScopes).content() }
    }
}

/** Hands item content a [ProfiledItemScope], reusing it while the list keeps the same item scope (one per list). */
private class ProfiledItemScopes(private val specs: ListItemSpecs) {
    private var cached: ProfiledItemScope? = null

    fun wrap(scope: LazyItemScope): LazyItemScope {
        cached?.let { if (it.delegate === scope) return it }
        return ProfiledItemScope(scope, specs).also { cached = it }
    }
}

private class ProfiledItemScope(
    val delegate: LazyItemScope,
    private val specs: ListItemSpecs,
) : LazyItemScope by delegate {
    override fun Modifier.animateItem(
        fadeInSpec: FiniteAnimationSpec<Float>?,
        placementSpec: FiniteAnimationSpec<IntOffset>?,
        fadeOutSpec: FiniteAnimationSpec<Float>?,
    ): Modifier {
        val specs = specs
        return with(delegate) {
            this@animateItem.animateItem(
                fadeInSpec = specs.fadeIn(fadeInSpec),
                placementSpec = specs.placement(placementSpec),
                fadeOutSpec = specs.fadeOut(fadeOutSpec),
            )
        }
    }
}

/**
 * Forwards every item to [delegate] with its content running on a profiled item scope. The hidden deprecated
 * overloads are overridden too, so binaries compiled against older foundation versions are wrapped as well instead
 * of reaching [delegate] through `by`.
 */
private class ProfiledListScope(
    private val delegate: LazyListScope,
    private val itemScopes: ProfiledItemScopes,
) : LazyListScope by delegate {
    override fun item(key: Any?, contentType: Any?, content: @Composable LazyItemScope.() -> Unit) {
        val itemScopes = itemScopes
        delegate.item(key, contentType) { itemScopes.wrap(this).content() }
    }

    override fun items(
        count: Int,
        key: ((index: Int) -> Any)?,
        contentType: (index: Int) -> Any?,
        itemContent: @Composable LazyItemScope.(index: Int) -> Unit,
    ) {
        val itemScopes = itemScopes
        delegate.items(count, key, contentType) { index -> itemScopes.wrap(this).itemContent(index) }
    }

    override fun stickyHeader(
        key: Any?,
        contentType: Any?,
        content: @Composable LazyItemScope.(stickyHeaderIndex: Int) -> Unit,
    ) {
        val itemScopes = itemScopes
        delegate.stickyHeader(key, contentType) { index -> itemScopes.wrap(this).content(index) }
    }

    @Deprecated("Binary compatibility only", level = DeprecationLevel.HIDDEN)
    override fun item(key: Any?, content: @Composable LazyItemScope.() -> Unit) {
        item(key, null, content)
    }

    @Deprecated("Binary compatibility only", level = DeprecationLevel.HIDDEN)
    override fun items(
        count: Int,
        key: ((index: Int) -> Any)?,
        itemContent: @Composable LazyItemScope.(index: Int) -> Unit,
    ) {
        items(count, key, { null }, itemContent)
    }

    @Deprecated("Binary compatibility only", level = DeprecationLevel.HIDDEN)
    override fun stickyHeader(key: Any?, contentType: Any?, content: @Composable LazyItemScope.() -> Unit) {
        stickyHeader(key, contentType) { _ -> content() }
    }
}

/**
 * Profile-aware `Modifier.animateItem()` for lists that are not run through [profiledListContent] (plain
 * `LazyColumn`s), as a plain function so upstream files hook it fully qualified:
 * `modifier = haruka.motion.listItemMotion(this)` in place of `Modifier.animateItem()`. Pass the item's [scope]
 * (`this` in `item {}`).
 *
 * STANDARD makes upstream's exact call: `animateItem()`, or `animateItem(fadeInSpec = null, fadeOutSpec = null)` when
 * [fade] is false. With [animateInStandard] false the item stays static in STANDARD, for lists Mihon does not animate.
 * Other profiles map those same calls through [listItemSpecs].
 */
@Composable
fun listItemMotion(
    scope: LazyItemScope,
    fade: Boolean = true,
    animateInStandard: Boolean = true,
): Modifier = with(scope) {
    val profile = LocalAnimationProfile.current
    val motionScheme = MaterialTheme.motionScheme
    val specs = remember(profile, motionScheme) { listItemSpecs(profile, motionScheme) }
    val fadeSpec = if (fade) DefaultItemFade else null
    when {
        specs != null -> Modifier.animateItem(
            fadeInSpec = specs.fadeIn(fadeSpec),
            placementSpec = specs.placement(DefaultItemPlacement),
            fadeOutSpec = specs.fadeOut(fadeSpec),
        )
        !animateInStandard -> Modifier
        fade -> Modifier.animateItem()
        else -> Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null)
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
 * Profile-aware drop-in for `androidx.compose.animation.animateContentSize`: upstream files swap that import for this
 * one and keep their calls. STANDARD makes upstream's call with the same arguments. EXPRESSIVE resizes quickly and
 * without a bounce: an overshooting height clips and re-exposes content, and a container whose content swaps first
 * (the filter / sort / display pager) should reach its new height right after, not half a second later. REDUCED
 * resizes instantly.
 */
@Composable
fun Modifier.animateContentSize(
    animationSpec: FiniteAnimationSpec<IntSize> = spring(
        stiffness = Spring.StiffnessMediumLow,
        visibilityThreshold = IntSize.VisibilityThreshold,
    ),
    finishedListener: ((initialValue: IntSize, targetValue: IntSize) -> Unit)? = null,
): Modifier =
    when (LocalAnimationProfile.current) {
        AnimationProfile.STANDARD -> foundationAnimateContentSize(animationSpec, finishedListener)
        AnimationProfile.EXPRESSIVE -> foundationAnimateContentSize(ExpressiveSizeSpec, finishedListener)
        AnimationProfile.REDUCED -> this
    }

// Medium stiffness, critically damped: settles in about 0.2 s, so the frame keeps up with content that already moved
private val ExpressiveSizeSpec: FiniteAnimationSpec<IntSize> = spring(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMedium,
    visibilityThreshold = IntSize.VisibilityThreshold,
)

/** Material 3 Expressive's default spatial stiffness; used without its damping where a bounce would look broken. */
internal const val EXPRESSIVE_SPATIAL_STIFFNESS = 380f

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
