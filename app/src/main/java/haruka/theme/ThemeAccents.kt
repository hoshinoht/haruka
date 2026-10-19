package haruka.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import eu.kanade.domain.ui.model.AppTheme

/** Shapes and typography handed to `MaterialExpressiveTheme` next to the colour scheme; motion follows the animation profile. */
internal class ThemeAccents(
    val shapes: Shapes,
    val typography: Typography,
)

/** [zenMaruBody]: whether Zen Maru Gothic's body weights have loaded (see [rememberZenMaruBodyLoaded]). */
internal fun themeAccents(appTheme: AppTheme, zenMaruBody: Boolean): ThemeAccents = when {
    appTheme != AppTheme.HOSHINO -> UpstreamAccents
    zenMaruBody -> HoshinoAccents
    else -> HoshinoHeadingAccents
}

/**
 * What `MaterialExpressiveTheme` uses when given nothing. Passed explicitly rather than as null so a
 * nested theme (the theme picker previews) does not inherit Hoshino's accents from the outer theme.
 */
private val UpstreamAccents = ThemeAccents(
    shapes = Shapes(),
    typography = Typography(),
)

/** Zen Maru Gothic's natural line height: (hhea ascent 1160 + descent 288) / 1000 units per em. */
private const val ZEN_MARU_LINE_HEIGHT_EM = 1.448f

/**
 * Where that exceeds Material's line height (bodyMedium, bodySmall, labelLarge, labelMedium), the default
 * line-height style pads single lines out to the font's height, making every list row a few px taller than with the
 * system font. Tight with both ends trimmed holds the line box at Material's height; descenders (216 units) still fit
 * below the centred baseline.
 */
private fun TextStyle.rounded(): TextStyle {
    val tallerThanLine = lineHeight.value < fontSize.value * ZEN_MARU_LINE_HEIGHT_EM
    return copy(
        fontFamily = ZenMaruGothic,
        lineHeightStyle = if (tallerThanLine) {
            (lineHeightStyle ?: LineHeightStyle.Default)
                .copy(trim = LineHeightStyle.Trim.Both, mode = LineHeightStyle.Mode.Tight)
        } else {
            lineHeightStyle
        },
    )
}

private fun TextStyle.roundedBold() = copy(fontFamily = ZenMaruGothic, fontWeight = FontWeight.Bold)

// Rounded corners and bold rounded headings echo the MomoTalk look.
private val HoshinoShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
    largeIncreased = RoundedCornerShape(28.dp),
    extraLargeIncreased = RoundedCornerShape(36.dp),
    extraExtraLarge = RoundedCornerShape(48.dp),
)

/** Until the body weights load (or when they cannot): headings use the bundled Bold, body and label the system font. */
private val HoshinoHeadingAccents = ThemeAccents(
    shapes = HoshinoShapes,
    typography = Typography().run {
        copy(
            displayLarge = displayLarge.roundedBold(),
            displayMedium = displayMedium.roundedBold(),
            displaySmall = displaySmall.roundedBold(),
            headlineLarge = headlineLarge.roundedBold(),
            headlineMedium = headlineMedium.roundedBold(),
            headlineSmall = headlineSmall.roundedBold(),
            titleLarge = titleLarge.roundedBold(),
            titleMedium = titleMedium.roundedBold(),
            titleSmall = titleSmall.roundedBold(),
        )
    },
)

/**
 * Zen Maru Gothic throughout. Body text uses Medium: the font's Regular is lighter than the system font and reads
 * thin on phone screens. Labels keep Material's weights (labelLarge/labelMedium are already Medium).
 */
private val HoshinoAccents = ThemeAccents(
    shapes = HoshinoShapes,
    typography = HoshinoHeadingAccents.typography.run {
        copy(
            bodyLarge = bodyLarge.rounded().copy(fontWeight = FontWeight.Medium),
            bodyMedium = bodyMedium.rounded().copy(fontWeight = FontWeight.Medium),
            bodySmall = bodySmall.rounded().copy(fontWeight = FontWeight.Medium),
            labelLarge = labelLarge.rounded(),
            labelMedium = labelMedium.rounded(),
            labelSmall = labelSmall.rounded(),
        )
    },
)
