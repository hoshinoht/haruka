package haruka.preference

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.BuildConfig
import haruka.migration.SourcePreset
import haruka.migration.SourcePresets
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

    /** Load details and chapters of a series added from a listing (browse, global search, related titles). */
    val fetchOnAdd: Preference<Boolean> = preferenceStore.getBoolean("haruka_fetch_on_add", true)

    /** Open a series left on the Default reading mode as a long strip when its tags or page shapes say it is one. */
    val autoReadingMode: Preference<Boolean> = preferenceStore.getBoolean("haruka_auto_reading_mode", true)

    /** Ids of series the automatic reading mode has decided, so none is detected twice. Device-local, never synced. */
    val autoReadingModeDecided: Preference<Set<String>> =
        preferenceStore.getStringSet(Preference.appStateKey("haruka_auto_reading_mode_decided"), emptySet())

    /** The library as one scroll: a pinned Collection header and dividers that follow the sort. */
    val libraryShelves: Preference<Boolean> = preferenceStore.getBoolean("haruka_library_shelves", false)

    // Per-shelf visibility under Library shelves
    val libraryShelfContinueReading: Preference<Boolean> =
        preferenceStore.getBoolean("haruka_library_shelf_continue_reading", true)
    val libraryShelfNewChapters: Preference<Boolean> =
        preferenceStore.getBoolean("haruka_library_shelf_new_chapters", true)
    val libraryShelfNotStarted: Preference<Boolean> =
        preferenceStore.getBoolean("haruka_library_shelf_not_started", true)
    val libraryShelfNeedsNewSource: Preference<Boolean> =
        preferenceStore.getBoolean("haruka_library_shelf_needs_new_source", true)
    val libraryShelfInflatedCounts: Preference<Boolean> =
        preferenceStore.getBoolean("haruka_library_shelf_inflated_counts", true)

    /** Motion across the app; pill swipe actions are part of [AnimationProfile.EXPRESSIVE]. */
    val animationProfile: Preference<AnimationProfile> =
        preferenceStore.getEnum("haruka_animation_profile", AnimationProfile.EXPRESSIVE)

    /** Named, ordered target-source lists for migration; synced like other fork settings. */
    val migrationSourcePresets: Preference<List<SourcePreset>> = preferenceStore.getObjectFromString(
        key = "haruka_migration_source_presets",
        defaultValue = emptyList(),
        serializer = SourcePresets::encode,
        deserializer = SourcePresets::decode,
    )

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
