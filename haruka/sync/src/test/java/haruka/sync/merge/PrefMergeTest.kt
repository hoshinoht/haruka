package haruka.sync.merge

import haruka.sync.model.PrefRecord
import haruka.sync.model.PrefsPayload
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PrefMergeTest {

    @Test
    fun `a remote change is applied locally`() {
        val base = prefs(COVERS to "true")
        val result = PrefMerge.merge(base, base, prefs(COVERS to "false"))

        result.merged shouldBe prefs(COVERS to "false")
        result.remoteDirty shouldBe false
        result.localChanges shouldBe PrefChangeSet(set = listOf(PrefRecord(COVERS, "false")))
    }

    @Test
    fun `a local change is committed`() {
        val base = prefs(COVERS to "true")
        val result = PrefMerge.merge(base, prefs(COVERS to "false"), base)

        result.merged shouldBe prefs(COVERS to "false")
        result.remoteDirty shouldBe true
        result.localChanges.isEmpty shouldBe true
    }

    @Test
    fun `a conflict keeps the syncing device's value and is counted`() {
        val result =
            PrefMerge.merge(prefs(PROFILE to "STANDARD"), prefs(PROFILE to "REDUCED"), prefs(PROFILE to "EXPRESSIVE"))

        result.merged shouldBe prefs(PROFILE to "REDUCED")
        result.conflicts shouldBe listOf(MergeConflict("pref $PROFILE", "value"))
    }

    @Test
    fun `resetting a preference on one side propagates`() {
        val base = prefs(COVERS to "false", RANKING to """["A"]""")
        val result = PrefMerge.merge(base, prefs(COVERS to "false"), prefs(RANKING to """["A"]"""))

        result.merged shouldBe PrefsPayload()
        // The remote reset covers, which this device applies; this device's own reset of the ranking is committed.
        result.localChanges shouldBe PrefChangeSet(remove = listOf(COVERS))
    }

    @Test
    fun `keys this build does not sync pass through untouched`() {
        val base = prefs(COVERS to "true", "haruka_future_pref" to "old")
        val result = PrefMerge.merge(
            base,
            prefs(COVERS to "true", "haruka_beta_updates" to "true", "haruka_future_pref" to "local"),
            prefs(COVERS to "true", "haruka_future_pref" to "remote"),
        )

        result.merged shouldBe prefs(COVERS to "true", "haruka_future_pref" to "remote")
        result.remoteDirty shouldBe false
        result.localChanges.isEmpty shouldBe true
    }

    @Test
    fun `union mode lets a set value beat an unset one and the syncing device win between set values`() {
        val result = PrefMerge.merge(
            base = null,
            local = prefs(COVERS to "false", PROFILE to "REDUCED"),
            remote = prefs(PROFILE to "STANDARD", RANKING to """["B"]"""),
        )

        result.merged shouldBe prefs(COVERS to "false", PROFILE to "REDUCED", RANKING to """["B"]""")
        result.localChanges shouldBe PrefChangeSet(set = listOf(PrefRecord(RANKING, """["B"]""")))
        result.conflicts.single().subject shouldBe "pref $PROFILE"
    }

    @Test
    fun `merging the base with itself changes nothing`() {
        val base = prefs(COVERS to "true", RANKING to """["A"]""")
        val result = PrefMerge.merge(base, base, base)

        result.merged shouldBe base
        result.remoteDirty shouldBe false
        result.localChanges.isEmpty shouldBe true
        result.conflicts.shouldBeEmpty()
    }

    @Test
    fun `whitelist excludes device-local and per-manga keys`() {
        SYNCED_PREF_KEYS.size shouldBe 17
        SYNCED_PREF_KEYS.any { it.startsWith("haruka_sync_") } shouldBe false
        ("haruka_beta_updates" in SYNCED_PREF_KEYS) shouldBe false
        ("haruka_dedup_manga_overrides" in SYNCED_PREF_KEYS) shouldBe false
    }

    private companion object {
        const val COVERS = "haruka_panorama_covers"
        const val PROFILE = "haruka_animation_profile"
        const val RANKING = "haruka_dedup_ranking"

        fun prefs(vararg values: Pair<String, String>) =
            PrefsPayload(values.map { PrefRecord(it.first, it.second) }.sortedBy { it.key })
    }
}
