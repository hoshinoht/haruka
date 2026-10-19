package haruka.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import eu.kanade.domain.ui.model.AppTheme
import haruka.di.harukaGraph
import haruka.motion.LocalAnimationProfile
import haruka.motion.effectiveAnimationProfile
import haruka.motion.motionScheme
import tachiyomi.presentation.core.util.collectAsState

/**
 * `MaterialExpressiveTheme` plus Haruka's theme inputs, swapped in for it by `TachiyomiTheme`: motion follows the
 * animation profile, shapes and typography follow [appTheme]'s accents, and [LocalAnimationProfile] is provided to
 * [content].
 */
@Composable
fun HarukaExpressiveTheme(
    appTheme: AppTheme,
    colorScheme: ColorScheme,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val accents = themeAccents(appTheme, zenMaruBody = appTheme == AppTheme.HOSHINO && rememberZenMaruBodyLoaded())
    val harukaPreferences = remember { context.harukaGraph.harukaPreferences }
    val chosenProfile by harukaPreferences.animationProfile.collectAsState()
    val profile = effectiveAnimationProfile(chosenProfile)
    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = profile.motionScheme(),
        shapes = accents.shapes,
        typography = accents.typography,
        content = {
            CompositionLocalProvider(
                LocalAnimationProfile provides profile,
                LocalHarukaAppTheme provides appTheme,
                content = content,
            )
        },
    )
}
