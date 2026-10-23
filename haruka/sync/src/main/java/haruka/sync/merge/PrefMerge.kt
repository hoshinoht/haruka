package haruka.sync.merge

import haruka.sync.model.PrefRecord
import haruka.sync.model.PrefsPayload
import haruka.sync.model.canonical

/**
 * Fork preferences that sync (plan section "Sync scope"), including the global dedup state. Never synced:
 * `haruka_beta_updates`, every `haruka_sync_*` key, and `haruka_dedup_manga_overrides`, which is keyed by local
 * manga id and syncs per entry instead.
 */
val SYNCED_PREF_KEYS: Set<String> = setOf(
    "haruka_panorama_covers",
    "haruka_group_updates",
    "haruka_related_manga",
    "haruka_compact_titles",
    "haruka_updates_momotalk",
    "haruka_fetch_on_add",
    "haruka_library_shelves",
    "haruka_library_shelf_continue_reading",
    "haruka_library_shelf_new_chapters",
    "haruka_library_shelf_not_started",
    "haruka_library_shelf_needs_new_source",
    "haruka_library_shelf_inflated_counts",
    "haruka_custom_shelves",
    "haruka_library_group_by",
    "haruka_animation_profile",
    "haruka_dedup_group_chapters",
    "haruka_dedup_merge_split",
    "haruka_dedup_mark_duplicates_read",
    "haruka_dedup_skip_downloaded_duplicates",
    "haruka_dedup_rules",
    "haruka_dedup_ranking",
    "haruka_migration_source_presets",
)

/** What to write to the local preferences so they match the merge. */
data class PrefChangeSet(
    val set: List<PrefRecord> = emptyList(),
    /** Keys to reset to their default. */
    val remove: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = set.isEmpty() && remove.isEmpty()
}

data class PrefMergeResult(
    val merged: PrefsPayload,
    /** True when [merged] differs from the remote payload and must be uploaded. */
    val remoteDirty: Boolean,
    val localChanges: PrefChangeSet,
    val conflicts: List<MergeConflict>,
)

/** Three-way merge of whitelisted fork preferences; an absent record is an unset preference. */
object PrefMerge {

    /**
     * @param base the base payload, or null in union mode (a set value then beats an unset one).
     * @param local this device's values; keys outside [SYNCED_PREF_KEYS] are ignored.
     * @param remote the remote payload, or null when the remote has none. Keys this build doesn't sync, e.g.
     *   from a newer version, pass through untouched.
     */
    fun merge(base: PrefsPayload?, local: PrefsPayload, remote: PrefsPayload?): PrefMergeResult {
        val b = base?.prefs.orEmpty().associate { it.key to it.value }
        val l = local.prefs.filter { it.key in SYNCED_PREF_KEYS }.associate { it.key to it.value }
        val remotePayload = remote?.canonical() ?: PrefsPayload()
        val r = remotePayload.prefs.associate { it.key to it.value }

        val conflicts = ArrayList<MergeConflict>()
        val merged = ArrayList<PrefRecord>()
        val set = ArrayList<PrefRecord>()
        val remove = ArrayList<String>()
        for (key in (SYNCED_PREF_KEYS + r.keys).sorted()) {
            val value = if (key in SYNCED_PREF_KEYS) {
                conflicts.pickSetting("pref $key", "value", b[key], l[key], r[key])
            } else {
                r[key]
            }
            if (value != null) merged += PrefRecord(key, value)
            if (key !in SYNCED_PREF_KEYS || value == l[key]) continue
            if (value == null) remove += key else set += PrefRecord(key, value)
        }

        val payload = PrefsPayload(merged)
        val dirty = payload != remotePayload
        return PrefMergeResult(
            merged = if (dirty) payload else remotePayload,
            remoteDirty = dirty,
            localChanges = PrefChangeSet(set, remove),
            conflicts = conflicts,
        )
    }
}
