package haruka.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import eu.kanade.presentation.theme.colorscheme.BaseColorScheme

/**
 * Takanashi Hoshino "Aquarium" palette: pale pink, aquarium cyan, halo gold on Abydos navy.
 *
 * Mihon colours unread chapter-count badges with `secondary` and downloaded badges with `tertiary`, so
 * `secondary` is pink and `tertiary` aqua. Keep in sync with colors_hoshino.xml.
 */
internal object HoshinoColorScheme : BaseColorScheme() {

    override val darkScheme = darkColorScheme(
        primary = Color(0xFFEFA7C2),
        onPrimary = Color(0xFF4A1530),
        primaryContainer = Color(0xFF7D3A59),
        onPrimaryContainer = Color(0xFFFFD8E7),
        inversePrimary = Color(0xFFA33769),
        secondary = Color(0xFFF4B8CF), // Unread badge
        onSecondary = Color(0xFF4A1530), // Unread badge text
        secondaryContainer = Color(0xFF6B3550), // Navigation pill, expanded chips
        onSecondaryContainer = Color(0xFFFFD8E7),
        tertiary = Color(0xFF8FD5E4), // Downloaded badge
        onTertiary = Color(0xFF0A3540),
        tertiaryContainer = Color(0xFF1F5562),
        onTertiaryContainer = Color(0xFFC9F1FA),
        background = Color(0xFF111B2B),
        onBackground = Color(0xFFF2EBF4),
        surface = Color(0xFF111B2B),
        onSurface = Color(0xFFF2EBF4),
        surfaceVariant = Color(0xFF26384F),
        onSurfaceVariant = Color(0xFFB0B6CD),
        surfaceTint = Color(0xFFEFA7C2),
        inverseSurface = Color(0xFFF2EBF4),
        inverseOnSurface = Color(0xFF172438),
        // Coral, not pink: error has to read apart from the pink primary (e.g. "18+" vs "Mixed" extension pills).
        error = Color(0xFFFF9473),
        onError = Color(0xFF5C1300),
        errorContainer = Color(0xFF8C2000),
        onErrorContainer = Color(0xFFFFDAD2),
        outline = Color(0xFF96A6BD),
        outlineVariant = Color(0xFF34465D),
        scrim = Color(0xFF000000),
        surfaceDim = Color(0xFF111B2B),
        surfaceBright = Color(0xFF34465D),
        surfaceContainerLowest = Color(0xFF0B1627),
        surfaceContainerLow = Color(0xFF142035),
        surfaceContainer = Color(0xFF172438), // Navigation bar
        surfaceContainerHigh = Color(0xFF1F2E44),
        surfaceContainerHighest = Color(0xFF26384F),
    )

    override val lightScheme = lightColorScheme(
        primary = Color(0xFFA33769),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFFFD8E7),
        onPrimaryContainer = Color(0xFF3D0A24),
        inversePrimary = Color(0xFFEFA7C2),
        secondary = Color(0xFFB04A7A), // Unread badge
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFF6D6E4),
        onSecondaryContainer = Color(0xFF3D0A24),
        tertiary = Color(0xFF087A92), // Downloaded badge
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFC9EEF6),
        onTertiaryContainer = Color(0xFF002F39),
        background = Color(0xFFFAF4F8),
        onBackground = Color(0xFF24334E),
        surface = Color(0xFFFAF4F8),
        onSurface = Color(0xFF24334E),
        surfaceVariant = Color(0xFFEBDFEB),
        onSurfaceVariant = Color(0xFF55596E),
        surfaceTint = Color(0xFFA33769),
        inverseSurface = Color(0xFF24334E),
        inverseOnSurface = Color(0xFFFAF4F8),
        error = Color(0xFFBA1A1A),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002),
        outline = Color(0xFF62667C),
        outlineVariant = Color(0xFFD9CBDA),
        scrim = Color(0xFF000000),
        surfaceDim = Color(0xFFE1D3E3),
        surfaceBright = Color(0xFFFFF8FC),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFFFF8FC),
        surfaceContainer = Color(0xFFF3EAF2), // Navigation bar
        surfaceContainerHigh = Color(0xFFEBDFEB),
        surfaceContainerHighest = Color(0xFFE1D3E3),
    )
}
