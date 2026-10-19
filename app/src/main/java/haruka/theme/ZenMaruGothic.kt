package haruka.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.GoogleFont
import eu.kanade.tachiyomi.R
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import androidx.compose.ui.text.googlefonts.Font as GoogleFontFont

private val GoogleFontsProvider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.haruka_font_certs,
)

private val ZenMaruGothicGoogleFont = GoogleFont("Zen Maru Gothic")

/**
 * Hoshino's typeface. Bold ships in the APK so headings render rounded offline and on the first frame. Regular and
 * Medium (body and label text) would add ~7.6 MB as full CJK files, so they come from the Google Fonts provider in
 * Play services, which caches them device-wide.
 */
internal val ZenMaruGothic = FontFamily(
    Font(R.font.haruka_zen_maru_gothic_bold, FontWeight.Bold),
    GoogleFontFont(ZenMaruGothicGoogleFont, GoogleFontsProvider, FontWeight.Normal),
    GoogleFontFont(ZenMaruGothicGoogleFont, GoogleFontsProvider, FontWeight.Medium),
)

// Process-wide so a new activity's theme starts on the loaded typography instead of flashing the system font again.
private var bodyWeightsLoaded by mutableStateOf(false)

/**
 * Whether [ZenMaruGothic]'s downloadable weights are loaded, starting the load if not. Hoshino switches body and label
 * text to Zen Maru Gothic only then, in one step: Zen Maru's taller metrics need a different line-height style than
 * the system font, so mixing per-text fallbacks would change row heights as each weight arrives. If the provider is
 * unavailable or offline, body text stays on the system font and the next theme composition retries.
 */
@Composable
internal fun rememberZenMaruBodyLoaded(): Boolean {
    if (!bodyWeightsLoaded) {
        val resolver = LocalFontFamilyResolver.current
        LaunchedEffect(resolver) {
            try {
                resolver.preload(ZenMaruGothic)
                bodyWeightsLoaded = true
            } catch (e: IllegalStateException) {
                logcat(LogPriority.WARN, e) { "Zen Maru Gothic body weights unavailable" }
            }
        }
    }
    return bodyWeightsLoaded
}
