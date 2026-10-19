package haruka.motion

import android.content.ContentResolver
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

/**
 * How much the UI moves. Every Haruka motion change reads [LocalAnimationProfile] and keeps Mihon's
 * exact behaviour for [STANDARD], so the profile is the single switch between upstream and Haruka motion.
 */
enum class AnimationProfile {
    /** Mihon's motion, unchanged. */
    STANDARD,

    /** Material 3 Expressive: spring-based transitions, shape morphs, pill swipe actions. */
    EXPRESSIVE,

    /** Minimal motion: short fades instead of slides, no overshoot, no decorative loops. */
    REDUCED,
}

/**
 * The profile in effect. Defaults to [AnimationProfile.STANDARD] so anything composed outside the app
 * theme (previews, upstream screens with their own theme) behaves exactly like Mihon.
 */
val LocalAnimationProfile = staticCompositionLocalOf { AnimationProfile.STANDARD }

/** The chosen profile, downgraded to [AnimationProfile.REDUCED] while the system "Remove animations" is on. */
@Composable
fun effectiveAnimationProfile(chosen: AnimationProfile): AnimationProfile {
    val resolver = LocalContext.current.contentResolver
    // Observed, so toggling the system setting with the app open takes effect without a restart.
    val animatorScale by produceState(animatorScale(resolver), resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                value = animatorScale(resolver)
            }
        }
        resolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            false,
            observer,
        )
        awaitDispose { resolver.unregisterContentObserver(observer) }
    }
    return if (animatorScale == 0f) AnimationProfile.REDUCED else chosen
}

private fun animatorScale(resolver: ContentResolver): Float =
    Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)

/**
 * Component motion for `MaterialExpressiveTheme`. Mihon already passes the expressive scheme, so both
 * [AnimationProfile.STANDARD] and [AnimationProfile.EXPRESSIVE] keep it; [AnimationProfile.REDUCED]
 * switches to the non-overshooting standard scheme.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
fun AnimationProfile.motionScheme(): MotionScheme = when (this) {
    AnimationProfile.REDUCED -> MotionScheme.standard()
    AnimationProfile.STANDARD, AnimationProfile.EXPRESSIVE -> MotionScheme.expressive()
}
