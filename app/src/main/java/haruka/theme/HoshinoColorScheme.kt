package haruka.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import eu.kanade.presentation.theme.colorscheme.BaseColorScheme

/**
 * Takanashi Hoshino / MomoTalk palette: pink primary, Abydos navy surfaces, blue and amber accents.
 *
 * The MomoTalk header pink (#FD90A3) and amber (#F5BE5A) are too light to carry white text, so the
 * light scheme uses them as containers and darker shades as the accent roles. Unread badges use
 * `secondary`, so they render in the blue accent. Keep in sync with colors_hoshino.xml.
 */
internal object HoshinoColorScheme : BaseColorScheme() {

    override val darkScheme = darkColorScheme(
        primary = Color(0xFFFD90A3),
        onPrimary = Color(0xFF5A0F26),
        primaryContainer = Color(0xFF8A2F4A),
        onPrimaryContainer = Color(0xFFFFD9E0),
        inversePrimary = Color(0xFFA63D5C),
        secondary = Color(0xFFA6C8F0),
        onSecondary = Color(0xFF0F2A4A),
        secondaryContainer = Color(0xFF34557C),
        onSecondaryContainer = Color(0xFFD6E5FA),
        tertiary = Color(0xFFF5BE5A),
        onTertiary = Color(0xFF412D00),
        tertiaryContainer = Color(0xFF5E4300),
        onTertiaryContainer = Color(0xFFFFDEA6),
        background = Color(0xFF18223A),
        onBackground = Color(0xFFE6ECF2),
        surface = Color(0xFF18223A),
        onSurface = Color(0xFFE6ECF2),
        surfaceVariant = Color(0xFF4C5B70),
        onSurfaceVariant = Color(0xFFD0DAE4),
        surfaceTint = Color(0xFFFD90A3),
        inverseSurface = Color(0xFFE6ECF2),
        inverseOnSurface = Color(0xFF1E2A44),
        error = Color(0xFFFF8A6B),
        onError = Color(0xFF5C1300),
        errorContainer = Color(0xFFA32A00),
        onErrorContainer = Color(0xFFFFDAD2),
        outline = Color(0xFF8C99AB),
        outlineVariant = Color(0xFF4C5B70),
        scrim = Color(0xFF000000),
        surfaceDim = Color(0xFF18223A),
        surfaceBright = Color(0xFF3A4763),
        surfaceContainerLowest = Color(0xFF111A2E),
        surfaceContainerLow = Color(0xFF1B2640),
        surfaceContainer = Color(0xFF1E2A44),
        surfaceContainerHigh = Color(0xFF28354F),
        surfaceContainerHighest = Color(0xFF33405B),
    )

    override val lightScheme = lightColorScheme(
        primary = Color(0xFFA63D5C),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFFD90A3),
        onPrimaryContainer = Color(0xFF3E0718),
        inversePrimary = Color(0xFFFFB1C0),
        secondary = Color(0xFF4C76A6),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFDCE5EC),
        onSecondaryContainer = Color(0xFF1E2A44),
        tertiary = Color(0xFF7B5800),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFF5BE5A),
        onTertiaryContainer = Color(0xFF2B1C00),
        background = Color(0xFFF3F7F8),
        onBackground = Color(0xFF1E2A44),
        surface = Color(0xFFF3F7F8),
        onSurface = Color(0xFF1E2A44),
        surfaceVariant = Color(0xFFDCE5EC),
        onSurfaceVariant = Color(0xFF4B5C6E),
        surfaceTint = Color(0xFFA63D5C),
        inverseSurface = Color(0xFF1E2A44),
        inverseOnSurface = Color(0xFFEDF2F4),
        error = Color(0xFFBF3000),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFFDAD2),
        onErrorContainer = Color(0xFF3D0800),
        outline = Color(0xFF75808E),
        outlineVariant = Color(0xFFC3CDD6),
        scrim = Color(0xFF000000),
        surfaceDim = Color(0xFFD3DDE3),
        surfaceBright = Color(0xFFF3F7F8),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFEDF2F4),
        surfaceContainer = Color(0xFFE7EEF1),
        surfaceContainerHigh = Color(0xFFE1E9ED),
        surfaceContainerHighest = Color(0xFFDCE5EC),
    )
}
