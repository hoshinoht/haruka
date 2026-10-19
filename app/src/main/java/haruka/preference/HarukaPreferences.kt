package haruka.preference

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.BuildConfig
import haruka.motion.AnimationProfile
import haruka.release.SemVer
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getEnum

/** Haruka-only preferences. Keys are prefixed `haruka_` so they never collide with upstream keys. */
@Inject
@SingleIn(AppScope::class)
class HarukaPreferences(preferenceStore: PreferenceStore) {

    val panoramaCovers: Preference<Boolean> = preferenceStore.getBoolean("haruka_panorama_covers", true)

    val groupUpdates: Preference<Boolean> = preferenceStore.getBoolean("haruka_group_updates", true)

    val relatedManga: Preference<Boolean> = preferenceStore.getBoolean("haruka_related_manga", true)

    val compactTitles: Preference<Boolean> = preferenceStore.getBoolean("haruka_compact_titles", true)

    val momotalkUpdates: Preference<Boolean> = preferenceStore.getBoolean("haruka_updates_momotalk", false)

    /** Motion across the app; pill swipe actions are part of [AnimationProfile.EXPRESSIVE]. */
    val animationProfile: Preference<AnimationProfile> =
        preferenceStore.getEnum("haruka_animation_profile", AnimationProfile.EXPRESSIVE)

    // Beta installs stay on the beta channel by default; stable installs only get stable releases.
    val betaUpdates: Preference<Boolean> = preferenceStore.getBoolean(
        "haruka_beta_updates",
        SemVer.parse(BuildConfig.VERSION_NAME)?.isPrerelease == true,
    )
}
