@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package haruka.motion

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.FloatingActionButtonElevation
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButtonColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableChipColors
import androidx.compose.material3.SelectableChipElevation
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.util.lerp
import androidx.compose.material3.FilledIconToggleButton as M3FilledIconToggleButton
import androidx.compose.material3.FilterChip as M3FilterChip
import androidx.compose.material3.IconButton as M3IconButton
import androidx.compose.material3.SmallExtendedFloatingActionButton as M3SmallExtendedFloatingActionButton

// Drop-ins for M3 components whose press squeezes their shape under EXPRESSIVE. Upstream files swap the androidx
// import for these and keep their calls. Each takes the M3 parameters with M3's defaults except `shape` and
// `interactionSource`, which belong to the morph; outside EXPRESSIVE the shape is exactly M3's default.

/**
 * [shape] squeezing towards the M3E `ButtonDefaults.shapes()` pressed shape while [interactionSource] is
 * pressed, for wrappers that already own an interaction source. STANDARD and REDUCED get [shape] back.
 */
@Composable
fun pressMorphShape(shape: Shape, interactionSource: InteractionSource): Shape {
    if (LocalAnimationProfile.current != AnimationProfile.EXPRESSIVE) return shape
    return morphShape(shape, shape, ButtonDefaults.shapes().pressedShape, selected = false, interactionSource)
}

/** M3 `IconButton` pressing towards M3E `IconButtonDefaults.shapes()`'s pressed shape. */
@Composable
fun IconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: IconButtonColors = IconButtonDefaults.iconButtonColors(),
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val shape = if (LocalAnimationProfile.current == AnimationProfile.EXPRESSIVE) {
        val shapes = IconButtonDefaults.shapes()
        morphShape(shapes.shape, shapes.shape, shapes.pressedShape, selected = false, interactionSource)
    } else {
        IconButtonDefaults.standardShape
    }
    M3IconButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = colors,
        interactionSource = interactionSource,
        shape = shape,
        content = content,
    )
}

/** M3 `FilledIconToggleButton` pressing and checking towards M3E `IconButtonDefaults.toggleableShapes()`. */
@Composable
fun FilledIconToggleButton(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: IconToggleButtonColors = IconButtonDefaults.filledIconToggleButtonColors(),
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val shape = if (LocalAnimationProfile.current == AnimationProfile.EXPRESSIVE) {
        val shapes = IconButtonDefaults.toggleableShapes()
        morphShape(shapes.shape, shapes.checkedShape, shapes.pressedShape, checked, interactionSource)
    } else {
        IconButtonDefaults.filledShape
    }
    M3FilledIconToggleButton(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        shape = shape,
        colors = colors,
        interactionSource = interactionSource,
        content = content,
    )
}

/**
 * M3 `SmallExtendedFloatingActionButton` with a press squeeze. M3 FABs take no `shapes`, so this borrows the pressed
 * shape M3E gives a button of the same 56 dp height (`ButtonDefaults.mediumPressedShape`).
 */
@Composable
fun SmallExtendedFloatingActionButton(
    text: @Composable () -> Unit,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    expanded: Boolean = true,
    containerColor: Color = FloatingActionButtonDefaults.containerColor,
    contentColor: Color = contentColorFor(containerColor),
    elevation: FloatingActionButtonElevation = FloatingActionButtonDefaults.elevation(),
) {
    val interactionSource = remember { MutableInteractionSource() }
    val restShape = FloatingActionButtonDefaults.smallExtendedFabShape
    val shape = if (LocalAnimationProfile.current == AnimationProfile.EXPRESSIVE) {
        morphShape(restShape, restShape, ButtonDefaults.mediumPressedShape, selected = false, interactionSource)
    } else {
        restShape
    }
    M3SmallExtendedFloatingActionButton(
        text = text,
        icon = icon,
        onClick = onClick,
        modifier = modifier,
        expanded = expanded,
        shape = shape,
        containerColor = containerColor,
        contentColor = contentColor,
        elevation = elevation,
        interactionSource = interactionSource,
    )
}

/** M3 `FilterChip` pressing and selecting towards M3E `FilterChipDefaults.shapes()`. */
@Composable
fun FilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    colors: SelectableChipColors = FilterChipDefaults.filterChipColors(),
    elevation: SelectableChipElevation? = FilterChipDefaults.filterChipElevation(),
    border: BorderStroke? = FilterChipDefaults.filterChipBorder(enabled, selected),
    horizontalArrangement: Arrangement.Horizontal = FilterChipDefaults.horizontalArrangement(),
    contentPadding: PaddingValues = FilterChipDefaults.ContentPadding,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val shape = if (LocalAnimationProfile.current == AnimationProfile.EXPRESSIVE) {
        val shapes = FilterChipDefaults.shapes()
        morphShape(shapes.shape, shapes.selectedShape, shapes.pressedShape, selected, interactionSource)
    } else {
        FilterChipDefaults.shape
    }
    M3FilterChip(
        selected = selected,
        onClick = onClick,
        label = label,
        modifier = modifier,
        enabled = enabled,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        shape = shape,
        colors = colors,
        elevation = elevation,
        border = border,
        horizontalArrangement = horizontalArrangement,
        contentPadding = contentPadding,
        interactionSource = interactionSource,
    )
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
