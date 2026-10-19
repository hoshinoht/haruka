package haruka.sync.merge

import haruka.sync.model.CategoriesPayload
import haruka.sync.model.CategoryRecord
import haruka.sync.model.canonical
import java.util.UUID

/** A local category for uuid assignment; [uuid] is null until the category has been mapped. */
data class LocalCategory(val id: Long, val uuid: String?, val name: String)

/** What to write to the local categories so they match the merge, in the plan's apply order. */
data class CategoryChangeSet(
    val create: List<CategoryRecord> = emptyList(),
    val rename: List<CategoryRecord> = emptyList(),
    val updateFlags: List<CategoryRecord> = emptyList(),
    /** Every live uuid in merged order, for `CategoryRepository.updateAllOrders`; empty when nothing moved. */
    val order: List<String> = emptyList(),
    val delete: List<String> = emptyList(),
) {
    val isEmpty: Boolean
        get() = create.isEmpty() && rename.isEmpty() && updateFlags.isEmpty() && order.isEmpty() && delete.isEmpty()
}

data class CategoryMergeResult(
    /** Live categories with dense orders 0..n-1, plus tombstones younger than [TOMBSTONE_RETENTION_MILLIS]. */
    val merged: CategoriesPayload,
    /** True when [merged] differs from the remote payload and must be uploaded. */
    val remoteDirty: Boolean,
    val localChanges: CategoryChangeSet,
    /** Uuids collapsed into a same-named category with a smaller uuid; pass to [EntryMergeOptions.categoryRemap]. */
    val remap: Map<String, String>,
    /** Every uuid that is not live after the merge; pass to [EntryMergeOptions.deletedCategories]. */
    val deleted: Set<String>,
    val conflicts: List<MergeConflict>,
)

/** Category identity (fork-owned uuids) and three-way merge (plan section "Merge rules"). */
object CategoryMerge {

    /**
     * Maps every local category to a uuid. Already mapped categories keep theirs; the rest take the uuid of an
     * unclaimed live remote category with the same name (exact match first, then case-insensitive), else a new one.
     * Returns local id to uuid.
     */
    fun assignUuids(
        local: List<LocalCategory>,
        remote: CategoriesPayload?,
        newUuid: () -> String = { UUID.randomUUID().toString() },
    ): Map<Long, String> {
        val result = LinkedHashMap<Long, String>()
        val claimed = HashSet<String>()
        for (category in local) {
            if (category.uuid != null) {
                result[category.id] = category.uuid
                claimed += category.uuid
            }
        }
        val candidates = remote?.categories.orEmpty().filter { it.deletedAtMillis == null }
        for (ignoreCase in booleanArrayOf(false, true)) {
            for (category in local) {
                if (category.id in result) continue
                val match = candidates.firstOrNull {
                    it.uuid !in claimed && it.name.equals(category.name, ignoreCase = ignoreCase)
                } ?: continue
                result[category.id] = match.uuid
                claimed += match.uuid
            }
        }
        for (category in local) {
            if (category.id !in result) result[category.id] = newUuid()
        }
        return result
    }

    /**
     * @param base the base payload, or null in union mode, where tombstones are never applied to a live local
     *   category (a category deleted elsewhere comes back).
     * @param local this device's live categories, already mapped to uuids.
     * @param remote the remote payload, or null when the remote has none.
     */
    fun merge(
        base: CategoriesPayload?,
        local: List<CategoryRecord>,
        remote: CategoriesPayload?,
        nowMillis: Long,
    ): CategoryMergeResult {
        val b = base?.canonical()?.categories.orEmpty().associateBy { it.uuid }
        val l = local.filter { it.deletedAtMillis == null }.associateBy { it.uuid }
        val remotePayload = remote?.canonical() ?: CategoriesPayload()
        val r = remotePayload.categories.associateBy { it.uuid }
        val uuids = sortedSetOf<String>().apply {
            addAll(b.keys)
            addAll(l.keys)
            addAll(r.keys)
        }

        val conflicts = ArrayList<MergeConflict>()
        val live = ArrayList<CategoryRecord>()
        val tombstones = ArrayList<CategoryRecord>()
        val deleted = HashSet<String>()
        for (uuid in uuids) {
            val bc = b[uuid]
            val lc = l[uuid]
            val rc = r[uuid]
            val isDeleted = if (bc != null) {
                // Delete wins over a concurrent edit: a base tombstone stays, and a side missing a category the
                // base had live deleted it (a missing remote record is a pruned remote tombstone).
                bc.deletedAtMillis != null || lc == null || rc == null || rc.deletedAtMillis != null
            } else {
                lc == null && rc != null && rc.deletedAtMillis != null
            }
            if (isDeleted) {
                deleted += uuid
                val deletedAt = maxOfNullable(bc?.deletedAtMillis, rc?.deletedAtMillis) ?: nowMillis
                if (!isExpired(deletedAt, nowMillis)) tombstones += (rc ?: bc!!).copy(deletedAtMillis = deletedAt)
                continue
            }
            val bv = bc ?: CategoryRecord(uuid)
            val lv = lc ?: bv
            val rv = rc?.takeIf { it.deletedAtMillis == null } ?: bv
            val subject = "category $uuid"
            live += CategoryRecord(
                uuid = uuid,
                name = conflicts.pickSetting(subject, "name", bv.name, lv.name, rv.name),
                order = conflicts.pickSetting(subject, "order", bv.order, lv.order, rv.order),
                flags = conflicts.pickSetting(subject, "flags", bv.flags, lv.flags, rv.flags),
            )
        }

        // Mihon keeps category names unique: equal names collapse into the smallest uuid (uuids iterate sorted).
        val remap = HashMap<String, String>()
        val survivors = LinkedHashMap<String, CategoryRecord>()
        for (category in live) {
            val survivor = survivors[category.name]
            if (survivor == null) {
                survivors[category.name] = category
            } else {
                remap[category.uuid] = survivor.uuid
                deleted += category.uuid
                tombstones += category.copy(deletedAtMillis = nowMillis)
            }
        }
        val ordered = survivors.values
            .sortedWith(compareBy<CategoryRecord> { it.order }.thenBy { it.uuid })
            .mapIndexed { index, category -> category.withOrder(index.toLong()) }

        val merged = CategoriesPayload(ordered + tombstones).canonical()
        val dirty = merged != remotePayload
        return CategoryMergeResult(
            merged = if (dirty) merged else remotePayload,
            remoteDirty = dirty,
            localChanges = localChanges(l, ordered),
            remap = remap,
            deleted = deleted,
            conflicts = conflicts,
        )
    }

    private fun localChanges(local: Map<String, CategoryRecord>, ordered: List<CategoryRecord>): CategoryChangeSet {
        val create = ArrayList<CategoryRecord>()
        val rename = ArrayList<CategoryRecord>()
        val updateFlags = ArrayList<CategoryRecord>()
        var moved = false
        for (category in ordered) {
            val lc = local[category.uuid]
            if (lc == null) {
                create += category
                moved = true
                continue
            }
            if (lc.name != category.name) rename += category
            if (lc.flags != category.flags) updateFlags += category
            if (lc.order != category.order) moved = true
        }
        val liveUuids = ordered.mapTo(HashSet()) { it.uuid }
        val delete = local.keys.filterNot(liveUuids::contains)
        return CategoryChangeSet(
            create = create,
            rename = rename,
            updateFlags = updateFlags,
            order = if (moved || delete.isNotEmpty()) ordered.map { it.uuid } else emptyList(),
            delete = delete,
        )
    }
}

private fun CategoryRecord.withOrder(order: Long): CategoryRecord =
    if (this.order == order) this else copy(order = order)
