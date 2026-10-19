package haruka.sync.merge

import haruka.sync.model.Bucket
import haruka.sync.model.CategoriesPayload
import haruka.sync.model.CategoryRecord
import haruka.sync.model.EntryKey
import haruka.sync.model.EntryRecord
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class CategoryMergeTest {

    @Test
    fun `bootstrap maps by exact name first, then case-insensitively, each remote uuid once`() {
        val remote = payload(
            cat("u1", "reading"),
            cat("u2", "Reading"),
            cat("u3", "Mine"),
            cat("u5", "Manga"),
            cat("u6", "Gone").copy(deletedAtMillis = NOW),
        )
        val local = listOf(
            LocalCategory(id = 1, uuid = null, name = "Reading"),
            LocalCategory(id = 2, uuid = null, name = "manga"),
            LocalCategory(id = 3, uuid = "u3", name = "Mine"),
            LocalCategory(id = 4, uuid = null, name = "READING"),
            LocalCategory(id = 5, uuid = null, name = "Gone"),
        )
        var next = 0

        CategoryMerge.assignUuids(local, remote) { "n${next++}" } shouldBe
            mapOf(1L to "u2", 2L to "u5", 3L to "u3", 4L to "u1", 5L to "n0")
    }

    @Test
    fun `a rename on one side and an assignment on the other both survive`() {
        val base = payload(cat("x", "Old"))
        val categories = CategoryMerge.merge(base, listOf(cat("x", "New")), base, NOW)

        categories.merged shouldBe payload(cat("x", "New"))
        categories.remoteDirty shouldBe true
        categories.localChanges.isEmpty shouldBe true

        val entries = mergeEntry(categories, base = "", local = "", remote = "x")
        entries.categoryUuids shouldBe listOf("x")
    }

    @Test
    fun `a remote rename is applied locally`() {
        val base = payload(cat("x", "Old", flags = 1))
        val result = CategoryMerge.merge(base, base.categories, payload(cat("x", "New", flags = 2)), NOW)

        result.localChanges.rename shouldBe listOf(cat("x", "New", flags = 2))
        result.localChanges.updateFlags shouldBe listOf(cat("x", "New", flags = 2))
        result.remoteDirty shouldBe false
    }

    @Test
    fun `a local delete wins over a remote assignment`() {
        val base = payload(cat("x", "X"))
        val categories = CategoryMerge.merge(base, emptyList(), base, NOW)

        categories.merged shouldBe payload(cat("x", "X").copy(deletedAtMillis = NOW))
        categories.deleted shouldBe setOf("x")
        categories.localChanges.isEmpty shouldBe true

        mergeEntry(categories, base = "", local = "", remote = "x").categoryUuids.shouldBeEmpty()
    }

    @Test
    fun `a remote delete wins over a local rename and drops local memberships`() {
        val base = payload(cat("x", "X"), cat("y", "Y", order = 1))
        val remote = payload(cat("x", "X").copy(deletedAtMillis = NOW - DAY), cat("y", "Y", order = 0))
        val categories = CategoryMerge.merge(base, listOf(cat("x", "Renamed"), cat("y", "Y", order = 1)), remote, NOW)

        categories.merged.categories.first() shouldBe cat("x", "X").copy(deletedAtMillis = NOW - DAY)
        categories.localChanges.delete shouldBe listOf("x")
        categories.conflicts.shouldBeEmpty()

        mergeEntry(categories, base = "", local = "x", remote = "").categoryUuids.shouldBeEmpty()
    }

    @Test
    fun `equal names collapse into the smaller uuid with memberships remapped`() {
        val base = payload(cat("a", "Fav"))
        val categories = CategoryMerge.merge(
            base,
            listOf(cat("a", "Fav"), cat("b2", "New", order = 1)),
            payload(cat("a", "Fav"), cat("a9", "New", order = 1)),
            NOW,
        )

        categories.merged shouldBe payload(
            cat("a", "Fav"),
            cat("a9", "New", order = 1),
            cat("b2", "New", order = 1).copy(deletedAtMillis = NOW, mergedIntoUuid = "a9"),
        )
        categories.remap shouldBe mapOf("b2" to "a9")
        categories.localChanges.create shouldBe listOf(cat("a9", "New", order = 1))
        categories.localChanges.delete shouldBe listOf("b2")

        val entry = mergeEntry(categories, base = "", local = "b2", remote = "a9")
        entry.categoryUuids shouldBe listOf("a9")
    }

    @Test
    fun `a collapsed category's tombstone is not rewritten by a re-merge`() {
        val base = payload(cat("u8", "B2"))
        val remote = payload(cat("r0", "Later", order = 1), cat("u8", "B2"))
        val first = CategoryMerge.merge(base, listOf(cat("u8", "Later")), remote, NOW)

        first.merged shouldBe payload(
            cat("r0", "Later"),
            cat("u8", "B2").copy(deletedAtMillis = NOW, mergedIntoUuid = "r0"),
        )
        first.remap shouldBe mapOf("u8" to "r0")
        val applied = first.merged.categories.filter { it.deletedAtMillis == null }
        // Against the pre-collapse remote the applied result reads as a plain local delete of u8: nothing left
        // names what it collapsed into. The engine never merges that state (it applies only after the commit
        // lands), so only the pointer may differ.
        val stale = CategoryMerge.merge(base, applied, remote, NOW).merged
        stale shouldBe first.merged.copy(categories = first.merged.categories.map { it.copy(mergedIntoUuid = null) })
        val settled = CategoryMerge.merge(first.merged, applied, first.merged, NOW + DAY)
        settled.merged shouldBe first.merged
        settled.remap.shouldBeEmpty()
    }

    @Test
    fun `a membership added to a category collapsed elsewhere moves to the survivor`() {
        // Device A renames u8 to the name r0 already has remotely and collapses u8 into r0.
        val base = payload(cat("u8", "B2"))
        val collapsed = CategoryMerge.merge(
            base,
            listOf(cat("u8", "Later")),
            payload(cat("r0", "Later", order = 1), cat("u8", "B2")),
            NOW,
        ).merged

        // Device B, still on the base, assigned an entry to u8 meanwhile and syncs after A.
        val categories = CategoryMerge.merge(base, base.categories, collapsed, NOW)

        categories.merged shouldBe collapsed
        categories.remap shouldBe mapOf("u8" to "r0")
        categories.localChanges.create shouldBe listOf(cat("r0", "Later"))
        categories.localChanges.delete shouldBe listOf("u8")
        mergeEntry(categories, base = "", local = "u8", remote = "").categoryUuids shouldBe listOf("r0")
    }

    @Test
    fun `a membership committed before a collapse moves to the survivor`() {
        // Device B committed an entry in u8; device A then collapses u8 into r0.
        val base = payload(cat("u8", "B2"))
        val categories = CategoryMerge.merge(
            base,
            listOf(cat("u8", "Later")),
            payload(cat("r0", "Later", order = 1), cat("u8", "B2")),
            NOW,
        )

        categories.remap shouldBe mapOf("u8" to "r0")
        mergeEntry(categories, base = "", local = "", remote = "u8").categoryUuids shouldBe listOf("r0")
    }

    @Test
    fun `a membership follows a chain of collapses to the live survivor`() {
        // c3 collapsed into c2, which later collapsed into c1; this device last synced before both.
        val base = payload(cat("c3", "Fav"))
        val remote = payload(
            cat("c1", "Fav"),
            cat("c2", "Fav").copy(deletedAtMillis = NOW - DAY, mergedIntoUuid = "c1"),
            cat("c3", "Fav").copy(deletedAtMillis = NOW - 2 * DAY, mergedIntoUuid = "c2"),
        )
        val categories = CategoryMerge.merge(base, base.categories, remote, NOW)

        categories.merged shouldBe remote
        categories.remap shouldBe mapOf("c2" to "c1", "c3" to "c1")
        mergeEntry(categories, base = "", local = "c3", remote = "").categoryUuids shouldBe listOf("c1")
    }

    @Test
    fun `a pointer cycle or a deleted survivor drops the membership`() {
        val base = payload(cat("x", "X"), cat("y", "Y", order = 1), cat("z", "Z", order = 2))
        val remote = payload(
            cat("x", "X").copy(deletedAtMillis = NOW - DAY, mergedIntoUuid = "y"),
            cat("y", "Y").copy(deletedAtMillis = NOW - DAY, mergedIntoUuid = "x"),
            cat("z", "Z").copy(deletedAtMillis = NOW - DAY, mergedIntoUuid = "gone"),
        )
        val categories = CategoryMerge.merge(base, base.categories, remote, NOW)

        categories.remap.shouldBeEmpty()
        categories.deleted shouldBe setOf("x", "y", "z")
        mergeEntry(categories, base = "", local = "x,y,z", remote = "").categoryUuids.shouldBeEmpty()
    }

    @Test
    fun `reorders on both sides keep the syncing device's order as one conflict`() {
        val base = payload(cat("A", "A", order = 0), cat("B", "B", order = 1), cat("C", "C", order = 2))
        val local = listOf(cat("C", "C", order = 0), cat("A", "A", order = 1), cat("B", "B", order = 2))
        val remote = payload(cat("B", "B", order = 0), cat("A", "A", order = 1), cat("C", "C", order = 2))
        val result = CategoryMerge.merge(base, local, remote, NOW)

        result.merged.categories.sortedBy { it.order }.map { it.uuid } shouldBe listOf("C", "A", "B")
        result.conflicts shouldBe listOf(MergeConflict("categories", "order"))
        result.localChanges.order.shouldBeEmpty()
    }

    @Test
    fun `a delete that renumbers this device keeps a remote move`() {
        val base = payload(cat("A", "A", order = 0), cat("B", "B", order = 1), cat("C", "C", order = 2))
        val local = listOf(cat("B", "B", order = 0), cat("C", "C", order = 1))
        val remote = payload(cat("A", "A", order = 1), cat("B", "B", order = 2), cat("C", "C", order = 0))
        val result = CategoryMerge.merge(base, local, remote, NOW)

        result.merged.categories.filter { it.deletedAtMillis == null }.sortedBy { it.order }.map { it.uuid } shouldBe
            listOf("C", "B")
        result.localChanges.order shouldBe listOf("C", "B")
        result.conflicts.shouldBeEmpty()
    }

    @Test
    fun `categories new on both sides interleave after the category they follow, by uuid`() {
        val base = payload(cat("A", "A", order = 0), cat("B", "B", order = 1))
        val local = listOf(cat("A", "A", order = 0), cat("n3", "Mine", order = 1), cat("B", "B", order = 2))
        val remote = payload(
            cat("n0", "First", order = 0),
            cat("A", "A", order = 1),
            cat("n1", "Theirs", order = 2),
            cat("B", "B", order = 3),
        )
        val result = CategoryMerge.merge(base, local, remote, NOW)
        val swapped = CategoryMerge.merge(base, remote.categories, payload(*local.toTypedArray()), NOW)

        result.merged.categories.sortedBy { it.order }.map { it.uuid } shouldBe listOf("n0", "A", "n1", "n3", "B")
        swapped.merged shouldBe result.merged
        CategoryMerge.merge(base, result.merged.categories, remote, NOW).merged shouldBe result.merged
        result.conflicts.shouldBeEmpty()
    }

    @Test
    fun `a remote reorder is applied locally as a dense order list`() {
        val base = payload(cat("A", "A", order = 0), cat("B", "B", order = 1))
        val result =
            CategoryMerge.merge(base, base.categories, payload(cat("A", "A", order = 1), cat("B", "B", order = 0)), NOW)

        result.localChanges.order shouldBe listOf("B", "A")
    }

    @Test
    fun `merged orders are dense`() {
        val remote = payload(cat("A", "A", order = 5), cat("B", "B", order = 9))
        val result = CategoryMerge.merge(null, emptyList(), remote, NOW)

        result.merged shouldBe payload(cat("A", "A", order = 0), cat("B", "B", order = 1))
        result.localChanges.create shouldBe result.merged.categories
        result.localChanges.order shouldBe listOf("A", "B")
    }

    @Test
    fun `conflicting names keep the syncing device's and are counted`() {
        val base = payload(cat("x", "Old"))
        val result = CategoryMerge.merge(base, listOf(cat("x", "Mine")), payload(cat("x", "Theirs")), NOW)

        result.merged shouldBe payload(cat("x", "Mine"))
        result.conflicts shouldBe listOf(MergeConflict("category x", "name"))
    }

    @Test
    fun `union mode never applies a remote tombstone to a live local category`() {
        val remote = payload(cat("x", "X").copy(deletedAtMillis = NOW))
        val result = CategoryMerge.merge(null, listOf(cat("x", "X")), remote, NOW)

        result.merged shouldBe payload(cat("x", "X"))
        result.deleted.shouldBeEmpty()
        result.localChanges.isEmpty shouldBe true
    }

    @Test
    fun `category tombstones are pruned after 180 days`() {
        val old = payload(cat("x", "X").copy(deletedAtMillis = NOW - 181 * DAY))
        val result = CategoryMerge.merge(old, emptyList(), old, NOW)

        result.merged shouldBe CategoriesPayload()
        result.deleted shouldBe setOf("x")
        result.remoteDirty shouldBe true
    }

    @Test
    fun `merging the base with itself changes nothing`() {
        val base = payload(cat("a", "A"), cat("b", "B", order = 1, flags = 3))
        val result = CategoryMerge.merge(base, base.categories, base, NOW)

        result.merged shouldBe base
        result.remoteDirty shouldBe false
        result.localChanges.isEmpty shouldBe true
        result.conflicts.shouldBeEmpty()
    }

    /** Merges one entry's memberships with this category merge's remap and deletions applied. */
    private fun mergeEntry(categories: CategoryMergeResult, base: String, local: String, remote: String): EntryRecord {
        fun entry(cats: String) =
            EntryRecord(KEY, favorite = true, categoryUuids = cats.split(',').filter(String::isNotEmpty))
        return EntryMerge.mergeBucket(
            index = 0,
            base = BaseBucket(Bucket(0, listOf(entry(base)))),
            local = listOf(entry(local)),
            remote = Bucket(0, listOf(entry(remote))),
            options = EntryMergeOptions(
                nowMillis = NOW,
                categoryRemap = categories.remap,
                deletedCategories = categories.deleted,
            ),
        ).merged.entries.single()
    }

    private companion object {
        val KEY = EntryKey(sourceId = 1, url = "/m/1")
        const val NOW = 1_800_000_000_000L
        const val DAY = 24L * 60 * 60 * 1000

        fun cat(uuid: String, name: String, order: Long = 0, flags: Long = 0) =
            CategoryRecord(uuid = uuid, name = name, order = order, flags = flags)

        fun payload(vararg categories: CategoryRecord) = CategoriesPayload(categories.sortedBy { it.uuid })
    }
}
