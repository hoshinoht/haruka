package haruka.motion

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.material3.MotionScheme
import androidx.compose.ui.unit.IntOffset
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test

class ListItemSpecsTest {

    private val scheme = MotionScheme.expressive()

    // What `Modifier.animateItem()` passes when called without arguments.
    private val defaultFade: FiniteAnimationSpec<Float> = spring(stiffness = Spring.StiffnessMediumLow)
    private val defaultPlacement: FiniteAnimationSpec<IntOffset> = spring(
        stiffness = Spring.StiffnessMediumLow,
        visibilityThreshold = IntOffset.VisibilityThreshold,
    )

    @Test
    fun `STANDARD has no mapping so upstream specs pass through untouched`() {
        listItemSpecs(AnimationProfile.STANDARD, scheme).shouldBeNull()
    }

    @Test
    fun `EXPRESSIVE maps library defaults to the motion scheme`() {
        val specs = listItemSpecs(AnimationProfile.EXPRESSIVE, scheme).shouldNotBeNull()

        specs.fadeIn(defaultFade) shouldBe scheme.defaultEffectsSpec<Float>()
        specs.placement(defaultPlacement) shouldBe scheme.defaultSpatialSpec<IntOffset>()
        specs.fadeOut(defaultFade) shouldBe scheme.fastEffectsSpec<Float>()
    }

    @Test
    fun `REDUCED maps library defaults to short fades and no placement motion`() {
        val specs = listItemSpecs(AnimationProfile.REDUCED, scheme).shouldNotBeNull()

        specs.fadeIn(defaultFade) shouldBe tween<Float>(150)
        specs.placement(defaultPlacement).shouldBeNull()
        specs.fadeOut(defaultFade) shouldBe tween<Float>(150)
    }

    @Test
    fun `null arguments stay null`() {
        listOf(AnimationProfile.EXPRESSIVE, AnimationProfile.REDUCED).forEach { profile ->
            val specs = listItemSpecs(profile, scheme).shouldNotBeNull()

            specs.fadeIn(null).shouldBeNull()
            specs.placement(null).shouldBeNull()
            specs.fadeOut(null).shouldBeNull()
        }
    }

    @Test
    fun `custom specs pass through`() {
        val fade = tween<Float>(300)
        val placement = spring(stiffness = Spring.StiffnessHigh, visibilityThreshold = IntOffset.VisibilityThreshold)
        listOf(AnimationProfile.EXPRESSIVE, AnimationProfile.REDUCED).forEach { profile ->
            val specs = listItemSpecs(profile, scheme).shouldNotBeNull()

            specs.fadeIn(fade) shouldBeSameInstanceAs fade
            specs.placement(placement) shouldBeSameInstanceAs placement
            specs.fadeOut(fade) shouldBeSameInstanceAs fade
        }
    }
}
