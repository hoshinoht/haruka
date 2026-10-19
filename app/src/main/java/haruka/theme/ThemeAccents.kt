package haruka.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.kanade.domain.ui.model.AppTheme
import eu.kanade.tachiyomi.R

/** Shapes and typography handed to `MaterialExpressiveTheme` next to the colour scheme; motion follows the animation profile. */
internal class ThemeAccents(
    val shapes: Shapes,
    val typography: Typography,
)

internal fun themeAccents(appTheme: AppTheme): ThemeAccents =
    if (appTheme == AppTheme.HOSHINO) HoshinoAccents else UpstreamAccents

/**
 * What `MaterialExpressiveTheme` uses when given nothing. Passed explicitly rather than as null so a
 * nested theme (the theme picker previews) does not inherit Hoshino's accents from the outer theme.
 */
private val UpstreamAccents = ThemeAccents(
    shapes = Shapes(),
    typography = Typography(),
)

private val ZenMaruGothic = FontFamily(Font(R.font.haruka_zen_maru_gothic_bold, FontWeight.Bold))

private fun TextStyle.rounded() = copy(fontFamily = ZenMaruGothic, fontWeight = FontWeight.Bold)

// Rounded headings and roomier corners echo the MomoTalk look; body and label text stay on the system font.
private val HoshinoAccents = ThemeAccents(
    shapes = Shapes(
        extraSmall = RoundedCornerShape(8.dp),
        small = RoundedCornerShape(12.dp),
        medium = RoundedCornerShape(20.dp),
        large = RoundedCornerShape(24.dp),
        extraLarge = RoundedCornerShape(32.dp),
        largeIncreased = RoundedCornerShape(28.dp),
        extraLargeIncreased = RoundedCornerShape(36.dp),
        extraExtraLarge = RoundedCornerShape(48.dp),
    ),
    typography = Typography().run {
        copy(
            displayLarge = displayLarge.rounded(),
            displayMedium = displayMedium.rounded(),
            displaySmall = displaySmall.rounded(),
            headlineLarge = headlineLarge.rounded(),
            headlineMedium = headlineMedium.rounded(),
            headlineSmall = headlineSmall.rounded(),
            titleLarge = titleLarge.rounded(),
            titleMedium = titleMedium.rounded(),
            titleSmall = titleSmall.rounded(),
        )
    },
)
