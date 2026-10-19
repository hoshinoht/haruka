package haruka.sync.local

import haruka.sync.merge.SYNCED_PREF_KEYS
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

/**
 * How a synced pref is stored. Enum and object preferences are stored as strings, so their stored form is the
 * string itself; booleans must be read and written as booleans (SharedPreferences rejects a typed mismatch).
 */
internal enum class PrefStorage { BOOLEAN, STRING }

/** Storage type of every key in [SYNCED_PREF_KEYS]. */
internal val SYNCED_PREF_STORAGE: Map<String, PrefStorage> = mapOf(
    "haruka_panorama_covers" to PrefStorage.BOOLEAN,
    "haruka_group_updates" to PrefStorage.BOOLEAN,
    "haruka_related_manga" to PrefStorage.BOOLEAN,
    "haruka_compact_titles" to PrefStorage.BOOLEAN,
    "haruka_updates_momotalk" to PrefStorage.BOOLEAN,
    "haruka_library_shelves" to PrefStorage.BOOLEAN,
    "haruka_animation_profile" to PrefStorage.STRING,
    "haruka_dedup_group_chapters" to PrefStorage.BOOLEAN,
    "haruka_dedup_merge_split" to PrefStorage.BOOLEAN,
    "haruka_dedup_mark_duplicates_read" to PrefStorage.BOOLEAN,
    "haruka_dedup_skip_downloaded_duplicates" to PrefStorage.BOOLEAN,
    "haruka_dedup_rules" to PrefStorage.STRING,
    "haruka_dedup_ranking" to PrefStorage.STRING,
)

/** The typed preference behind synced [key]. */
internal fun PreferenceStore.syncedPreference(key: String): Preference<*> =
    when (SYNCED_PREF_STORAGE[key] ?: error("No storage type known for synced pref $key")) {
        PrefStorage.BOOLEAN -> getBoolean(key)
        PrefStorage.STRING -> getString(key)
    }
