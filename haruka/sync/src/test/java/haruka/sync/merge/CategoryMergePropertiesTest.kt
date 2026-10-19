package haruka.sync.merge

import haruka.sync.merge.MergeArbs.NOW
import haruka.sync.merge.MergeArbs.check
import haruka.sync.merge.MergeArbs.live
import haruka.sync.model.CategoriesPayload
import haruka.sync.model.CategoryRecord
import haruka.sync.model.canonical
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * P1–P5 of plan section "Test properties" for [CategoryMerge]. Name, order and flags are all syncing-device-wins
 * settings, so P3 covers what stays: which categories are live and which are deleted.
 */
class CategoryMergePropertiesTest {

    @Test
    fun `P1 merging the base with itself changes nothing`() = check("P1 categories no-op", MergeArbs.categorySynced) {
        val result = merge(it, live(it), it)

        result.merged shouldBe it
        result.remoteDirty shouldBe false
        result.localChanges.isEmpty shouldBe true
        result.remap.shouldBeEmpty()
        result.deleted shouldBe it.categories.filter { c -> c.deletedAtMillis != null }.map { c -> c.uuid }.toSet()
        result.conflicts.shouldBeEmpty()
    }

    @Test
    fun `P2 a change made only on this device is kept`() =
        check("P2 categories local-only", MergeArbs.categoryLocalOnly) { s ->
            val base = s.base!!
            val result = merge(base, s.local, s.remote)
            val kept = s.local.mapTo(HashSet()) { it.uuid }
            val deletedHere = live(base).filter { it.uuid !in kept }.map { it.copy(deletedAtMillis = NOW) }
            val tombstones = base.categories.filter { it.deletedAtMillis != null }

            result.merged shouldBe CategoriesPayload(s.local + deletedHere + tombstones).canonical()
            result.localChanges.isEmpty shouldBe true
            result.remap.shouldBeEmpty()
            result.conflicts.shouldBeEmpty()
        }

    @Test
    fun `P2 a change made only on the remote is taken`() =
        check("P2 categories remote-only", MergeArbs.categoryRemoteOnly) { s ->
            val result = merge(s.base, s.local, s.remote)

            result.merged shouldBe s.remote
            result.remoteDirty shouldBe false
            result.conflicts.shouldBeEmpty()
            applyLocal(s.local, result.localChanges) shouldBe live(s.remote).sortedBy { it.uuid }
        }

    @Test
    fun `P3 liveness is symmetric`() = check("P3 categories symmetry", MergeArbs.categorySymmetric) { s ->
        val ab = merge(s.base, s.a, s.bPayload)
        val ba = merge(s.base, s.b, s.aPayload)

        live(ab.merged).map { it.uuid } shouldBe live(ba.merged).map { it.uuid }
        ab.deleted shouldBe ba.deleted
    }

    @Test
    fun `P4 merging is idempotent in every field`() = check(
        "P4 categories idempotence",
        MergeArbs.categoryTwoSided,
    ) { s ->
        val m = merge(s.base, s.local, s.remote).merged

        // Against the pre-merge remote, a collapse loser the base had live reads as a plain local delete: nothing
        // left names its survivor. The engine never merges that state (it applies only after the commit lands),
        // so that merge may only lose collapse pointers, never change or add one.
        val stale = merge(s.base, live(m), s.remote).merged
        stale.withoutPointers() shouldBe m.withoutPointers()
        val pointers = m.pointers()
        stale.pointers().filter { (uuid, into) -> pointers[uuid] != into }.shouldBeEmpty()
        val settled = merge(m, live(m), m)
        settled.merged shouldBe m
        settled.remoteDirty shouldBe false
        settled.localChanges.isEmpty shouldBe true
        settled.remap.shouldBeEmpty()
        settled.conflicts.shouldBeEmpty()
    }

    @Test
    fun `P5 union mode never loses a live category`() = check("P5 categories union", MergeArbs.categoryUnion) { s ->
        val result = merge(null, s.local, s.remote)
        val merged = live(result.merged).mapTo(HashSet()) { it.uuid }

        // Tombstones are never applied in union mode; a category collapsed into a same-named one lives on as it.
        for (uuid in (s.local + live(s.remote)).map { it.uuid }) {
            val survivor = result.remap[uuid] ?: uuid
            if (survivor !in merged) throw AssertionError("$uuid lost (survivor $survivor)")
        }
    }

    private fun merge(base: CategoriesPayload?, local: List<CategoryRecord>, remote: CategoriesPayload?) =
        CategoryMerge.merge(base, local, remote, NOW)

    /** What an applier following the change set leaves locally, sorted by uuid. */
    private fun applyLocal(local: List<CategoryRecord>, changes: CategoryChangeSet): List<CategoryRecord> {
        val byUuid = local.associateByTo(HashMap()) { it.uuid }
        for (category in changes.create) byUuid[category.uuid] = category
        for (category in changes.rename) {
            byUuid[category.uuid] =
                byUuid.getValue(category.uuid).copy(name = category.name)
        }
        for (category in changes.updateFlags) {
            byUuid[category.uuid] = byUuid.getValue(category.uuid).copy(flags = category.flags)
        }
        for (uuid in changes.delete) byUuid -= uuid
        changes.order.forEachIndexed { i, uuid -> byUuid[uuid] = byUuid.getValue(uuid).copy(order = i.toLong()) }
        return byUuid.values.sortedBy { it.uuid }
    }

    private fun CategoriesPayload.pointers(): Map<String, String> =
        categories.mapNotNull { c -> c.mergedIntoUuid?.let { c.uuid to it } }.toMap()

    private fun CategoriesPayload.withoutPointers(): CategoriesPayload =
        copy(categories = categories.map { it.copy(mergedIntoUuid = null) })
}
