@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package haruka.motion

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.util.lerp

/**
 * A shape plus the interaction source that drives it. Pass both to the same component so its presses
 * reach the shape. Outside EXPRESSIVE [shape] is exactly the component's upstream default.
 */
@Immutable
class PressMorph internal constructor(
    val shape: Shape,
    val interactionSource: MutableInteractionSource,
)

/**
 * [shape] squeezing towards the M3E `ButtonDefaults.shapes()` pressed shape while [interactionSource] is
 * pressed, for wrappers that already own an interaction source. STANDARD and REDUCED get [shape] back.
 */
@Composable
fun pressMorphShape(shape: Shape, interactionSource: InteractionSource): Shape {
    if (LocalAnimationProfile.current != AnimationProfile.EXPRESSIVE) return shape
    return morphShape(shape, shape, ButtonDefaults.shapes().pressedShape, selected = false, interactionSource)
}

/** Press morph for an `IconButton` using M3E `IconButtonDefaults.shapes()`. */
@Composable
fun rememberIconButtonPressMorph(): PressMorph {
    val interactionSource = remember { MutableInteractionSource() }
    val shape = if (LocalAnimationProfile.current == AnimationProfile.EXPRESSIVE) {
        val shapes = IconButtonDefaults.shapes()
        morphShape(shapes.shape, shapes.shape, shapes.pressedShape, selected = false, interactionSource)
    } else {
        IconButtonDefaults.standardShape
    }
    return remember(shape, interactionSource) { PressMorph(shape, interactionSource) }
}

/**
 * Press and checked morph for a filled icon toggle button using M3E `IconButtonDefaults.toggleableShapes()`.
 * Outside EXPRESSIVE the shape is `IconButtonDefaults.filledShape`, upstream's default.
 */
@Composable
fun rememberFilledIconToggleButtonPressMorph(checked: Boolean): PressMorph {
    val interactionSource = remember { MutableInteractionSource() }
    val shape = if (LocalAnimationProfile.current == AnimationProfile.EXPRESSIVE) {
        val shapes = IconButtonDefaults.toggleableShapes()
        morphShape(shapes.shape, shapes.checkedShape, shapes.pressedShape, checked, interactionSource)
    } else {
        IconButtonDefaults.filledShape
    }
    return remember(shape, interactionSource) { PressMorph(shape, interactionSource) }
}

/**
 * Press morph for a small extended FAB. M3 FABs take no `shapes`, so this borrows the pressed shape M3E
 * gives a button of the same 56 dp height (`ButtonDefaults.mediumPressedShape`).
 * Outside EXPRESSIVE the shape is `FloatingActionButtonDefaults.smallExtendedFabShape`, upstream's default.
 */
@Composable
fun rememberSmallExtendedFabPressMorph(): PressMorph {
    val interactionSource = remember { MutableInteractionSource() }
    val restShape = FloatingActionButtonDefaults.smallExtendedFabShape
    val shape = if (LocalAnimationProfile.current == AnimationProfile.EXPRESSIVE) {
        morphShape(restShape, restShape, ButtonDefaults.mediumPressedShape, selected = false, interactionSource)
    } else {
        restShape
    }
    return remember(shape, interactionSource) { PressMorph(shape, interactionSource) }
}

/**
 * Press and selection morph for a `FilterChip` using M3E `FilterChipDefaults.shapes()`.
 * Outside EXPRESSIVE the shape is `FilterChipDefaults.shape`, upstream's default.
 */
@Composable
fun rememberFilterChipPressMorph(selected: Boolean): PressMorph {
    val interactionSource = remember { MutableInteractionSource() }
    val shape = if (LocalAnimationProfile.current == AnimationProfile.EXPRESSIVE) {
        val shapes = FilterChipDefaults.shapes()
        morphShape(shapes.shape, shapes.selectedShape, shapes.pressedShape, selected, interactionSource)
    } else {
        FilterChipDefaults.shape
    }
    return remember(shape, interactionSource) { PressMorph(shape, interactionSource) }
}

/**
 * Animates corner sizes from [shape] towards [selectedShape] and, while pressed, [pressedShape], like
 * M3E's internal `shapeByInteraction`. Uses the default effects spring, as M3E buttons do, so the squeeze
 * never overshoots. Shapes that are not corner based cannot be interpolated and are returned as is.
 */
@Composable
private fun morphShape(
    shape: Shape,
    selectedShape: Shape,
    pressedShape: Shape,
    selected: Boolean,
    interactionSource: InteractionSource,
): Shape {
    if (shape !is CornerBasedShape || selectedShape !is CornerBasedShape || pressedShape !is CornerBasedShape) {
        return shape
    }
    val spec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val pressed by interactionSource.collectIsPressedAsState()
    val pressedFraction = animateFloatAsState(if (pressed) 1f else 0f, spec, label = "pressMorph")
    val selectedFraction = animateFloatAsState(if (selected) 1f else 0f, spec, label = "selectMorph")
    return remember(shape, selectedShape, pressedShape, selectedFraction, pressedFraction) {
        MorphingCornerShape(shape, selectedShape, pressedShape, selectedFraction, pressedFraction)
    }
}

/** Reads the fractions in [createOutline], so a press re-outlines without recomposing the component. */
private class MorphingCornerShape(
    private val shape: CornerBasedShape,
    private val selectedShape: CornerBasedShape,
    private val pressedShape: CornerBasedShape,
    private val selectedFraction: State<Float>,
    private val pressedFraction: State<Float>,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val selected = selectedFraction.value
        val pressed = pressedFraction.value
        val maxCorner = size.minDimension / 2f
        fun corner(rest: CornerSize, selectedCorner: CornerSize, pressedCorner: CornerSize): Float {
            val unpressed = lerp(rest.toPx(size, density), selectedCorner.toPx(size, density), selected)
            return lerp(unpressed, pressedCorner.toPx(size, density), pressed).coerceIn(0f, maxCorner)
        }
        // Corner order is topStart, topEnd, bottomEnd, bottomStart.
        return shape.createOutline(
            size,
            corner(shape.topStart, selectedShape.topStart, pressedShape.topStart),
            corner(shape.topEnd, selectedShape.topEnd, pressedShape.topEnd),
            corner(shape.bottomEnd, selectedShape.bottomEnd, pressedShape.bottomEnd),
            corner(shape.bottomStart, selectedShape.bottomStart, pressedShape.bottomStart),
            layoutDirection,
        )
    }
}
