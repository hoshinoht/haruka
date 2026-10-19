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

        // A tombstone's payload is the remote's copy, then the base's, so re-merging an applied result rewrites
        // nothing; only a category neither side knows yet keeps its merged fields.
        fun bury(uuid: String, merged: CategoryRecord?) {
            deleted += uuid
            val bc = b[uuid]
            val rc = r[uuid]
            val deletedAt = maxOfNullable(bc?.deletedAtMillis, rc?.deletedAtMillis) ?: nowMillis
            if (!isExpired(deletedAt, nowMillis)) tombstones += (rc ?: bc ?: merged!!).copy(deletedAtMillis = deletedAt)
        }

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
                bury(uuid, merged = null)
                continue
            }
            val bv = bc ?: CategoryRecord(uuid)
            val lv = lc ?: bv
            val rv = rc?.takeIf { it.deletedAtMillis == null } ?: bv
            val subject = "category $uuid"
            live += CategoryRecord(
                uuid = uuid,
                name = conflicts.pickSetting(subject, "name", bv.name, lv.name, rv.name),
                // Placeholder: [order] merges the order over the whole live list.
                order = lv.order,
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
                bury(category.uuid, merged = category)
            }
        }
        val ordered = order(b, l, r, survivors.values, union = base == null, conflicts)
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

    /**
     * Orders [live]. The categories live on all three sides form one register: their relative order is the
     * syncing device's when it differs from the base's (a conflict when the remote's differs too), else the
     * remote's. A category new on one side follows the shared category it follows there, among the others new
     * after it by that side's position, then uuid; one new on both sides takes this device's place. Union mode
     * takes the remote's order and appends the categories live only here, in this device's order.
     */
    private fun order(
        b: Map<String, CategoryRecord>,
        l: Map<String, CategoryRecord>,
        r: Map<String, CategoryRecord>,
        live: Collection<CategoryRecord>,
        union: Boolean,
        conflicts: MutableList<MergeConflict>,
    ): List<CategoryRecord> {
        val byUuid = live.associateBy { it.uuid }
        val byOrder = compareBy<CategoryRecord>({ it.order }, { it.uuid })

        /** The live categories this side has live, in its order. */
        fun sequence(side: Map<String, CategoryRecord>): List<String> =
            live.mapNotNull { category -> side[category.uuid]?.takeIf { it.deletedAtMillis == null } }
                .sortedWith(byOrder)
                .map { it.uuid }

        val localSequence = sequence(l)
        val remoteSequence = sequence(r)
        if (union) {
            val onRemote = remoteSequence.toHashSet()
            return (remoteSequence + localSequence.filterNot(onRemote::contains)).map(byUuid::getValue)
        }

        // A live category the base has is live on every side: a delete anywhere wins.
        val baseSequence = sequence(b)
        val isShared = baseSequence.toHashSet()
        val shared = conflicts.pickSetting(
            subject = "categories",
            field = "order",
            base = baseSequence,
            local = localSequence.filter(isShared::contains),
            remote = remoteSequence.filter(isShared::contains),
        )
        // Sort key: the high half is 1 + the index of the shared category a category is or follows (0 before the
        // first one), the low half 1 + its position among the new categories following that one (0 for the shared
        // category itself, so it leads them). This device places a category new on both sides.
        val slot = HashMap<String, Long>(live.size)
        shared.forEachIndexed { index, uuid -> slot[uuid] = (index + 1L) shl 32 }
        for (sequence in listOf(localSequence, remoteSequence)) {
            var anchor = 0L
            var position = 0L
            for (uuid in sequence) {
                if (uuid in isShared) {
                    anchor = slot.getValue(uuid)
                    position = 0
                } else {
                    slot.putIfAbsent(uuid, anchor + 1 + position++)
                }
            }
        }
        return live.sortedWith(compareBy<CategoryRecord>({ slot.getValue(it.uuid) }, { it.uuid }))
    }
}

private fun CategoryRecord.withOrder(order: Long): CategoryRecord =
    if (this.order == order) this else copy(order = order)
