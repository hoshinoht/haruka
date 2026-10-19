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

    // Sync settings are device-local: app-state keys never go into Mihon backups, and no haruka_sync_* key syncs.

    /** Automatic syncs (periodic and triggers); a manual "Sync now" runs either way. */
    val syncEnabled: Preference<Boolean> =
        preferenceStore.getBoolean(Preference.appStateKey("haruka_sync_enabled"), false)

    /** Hours between periodic syncs; 0 turns the periodic sync off. */
    val syncIntervalHours: Preference<Int> =
        preferenceStore.getInt(Preference.appStateKey("haruka_sync_interval_hours"), 6)

    val syncWifiOnly: Preference<Boolean> =
        preferenceStore.getBoolean(Preference.appStateKey("haruka_sync_wifi_only"), false)

    val syncChargingOnly: Preference<Boolean> =
        preferenceStore.getBoolean(Preference.appStateKey("haruka_sync_charging_only"), false)

    /** The library in Drive is end-to-end encrypted with a key this device holds (wrapped by Android Keystore). */
    val syncEncryption: Preference<Boolean> =
        preferenceStore.getBoolean(Preference.appStateKey("haruka_sync_encryption"), false)
}
