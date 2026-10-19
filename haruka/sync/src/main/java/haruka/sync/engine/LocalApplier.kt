package haruka.sync.engine

import haruka.sync.merge.CategoryChangeSet
import haruka.sync.merge.LocalChangeSet
import haruka.sync.merge.PrefChangeSet
import haruka.sync.model.EntryKey
import haruka.sync.model.EntryRecord

/**
 * Write side of this device's library, implemented by the app glue (plan section "Apply path"). The engine calls it
 * only after the merged state is the remote head (or the merge changed nothing remotely), in this order:
 * [applyCategories] once, [applyEntries] once per bucket with changes (ascending), [applyPrefs] once. Calls whose
 * change set is empty are skipped.
 *
 * A throw aborts the sync like a crash: the old base stays active, so applied fields re-merge as no-ops and the
 * rest is applied by the next sync.
 */
interface LocalApplier {

    /**
     * Applies category creates, renames, flags, order and deletes. [localIds] maps every uuid live on this device
     * before the apply to its local category id. Returns the map after the apply.
     */
    suspend fun applyCategories(changes: CategoryChangeSet, localIds: Map<String, Long>): CategoryApplyResult

    /**
     * Inserts [LocalChangeSet.inserts] (with their full chapter index) and applies [LocalChangeSet.updates] to
     * existing rows; never inserts chapter rows into an existing entry and never deletes chapter rows.
     * `EntryUpdate.entry` carries the merged entry-level fields only (its `chapters` is empty); the chapter states
     * to write are in `EntryUpdate.chapters`. [categoryLocalIds] maps uuid to local category id.
     */
    suspend fun applyEntries(changes: LocalChangeSet, categoryLocalIds: Map<String, Long>): EntryApplyResult

    suspend fun applyPrefs(changes: PrefChangeSet): PrefApplyResult
}

data class CategoryApplyResult(
    /** Uuid to local id for every category live after the apply. */
    val localIds: Map<String, Long>,
    /** The optimistic guard skipped the categories: the base keeps its pre-merge categories, so they merge again. */
    val skipped: Boolean = false,
)

data class EntryApplyResult(
    /**
     * Entries the optimistic guard skipped (the row changed since the snapshot) or that failed to apply; the base
     * keeps their pre-merge value, so the next sync merges them again against the user's newer edit.
     */
    val skipped: Set<EntryKey> = emptySet(),
    /**
     * Entries re-read after the apply that don't match the merge (e.g. a history write that was swallowed), as the
     * snapshot reader would read them now. The base takes these actual values, so the next sync applies again.
     */
    val unrealized: Map<EntryKey, EntryRecord> = emptyMap(),
)

data class PrefApplyResult(
    /** The optimistic guard skipped the prefs; the base keeps its pre-merge prefs. */
    val skipped: Boolean = false,
)
