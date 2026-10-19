package haruka.sync.merge

import haruka.sync.merge.MergeArbs.check
import haruka.sync.model.PrefRecord
import haruka.sync.model.PrefsPayload
import haruka.sync.model.canonical
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * P1, P2, P4 and P5 of plan section "Test properties" for [PrefMerge]. Every whitelisted value is a
 * syncing-device-wins setting, so P3 has nothing to compare.
 */
class PrefMergePropertiesTest {

    @Test
    fun `P1 merging the base with itself changes nothing`() = check("P1 prefs no-op", MergeArbs.prefSynced) { s ->
        val result = merge(s.base, s.local, s.remote)

        result.merged shouldBe s.remote
        result.remoteDirty shouldBe false
        result.localChanges.isEmpty shouldBe true
        result.conflicts.shouldBeEmpty()
    }

    @Test
    fun `P2 a change made only on this device is kept`() = check("P2 prefs local-only", MergeArbs.prefLocalOnly) { s ->
        val result = merge(s.base, s.local, s.remote)

        // Keys this build doesn't sync keep the remote's (here the base's) values.
        result.merged shouldBe PrefsPayload(synced(s.local).prefs + unknown(s.remote).prefs).canonical()
        result.localChanges.isEmpty shouldBe true
        result.conflicts.shouldBeEmpty()
    }

    @Test
    fun `P2 a change made only on the remote is taken`() = check(
        "P2 prefs remote-only",
        MergeArbs.prefRemoteOnly,
    ) { s ->
        val result = merge(s.base, s.local, s.remote)

        result.merged shouldBe s.remote
        result.remoteDirty shouldBe false
        result.conflicts.shouldBeEmpty()
        applyLocal(synced(s.local), result.localChanges) shouldBe synced(s.remote)
    }

    @Test
    fun `P4 merging is idempotent in every field`() = check("P4 prefs idempotence", MergeArbs.prefTwoSided) { s ->
        val m = merge(s.base, s.local, s.remote).merged

        merge(s.base, synced(m), s.remote).merged shouldBe m
        val settled = merge(m, synced(m), m)
        settled.merged shouldBe m
        settled.remoteDirty shouldBe false
        settled.localChanges.isEmpty shouldBe true
        settled.conflicts.shouldBeEmpty()
    }

    @Test
    fun `P5 union mode never unsets a value`() = check("P5 prefs union", MergeArbs.prefUnion) { s ->
        val result = merge(null, s.local, s.remote)
        val merged = result.merged.prefs.associate { it.key to it.value }
        val set = (synced(s.local).prefs + s.remote.prefs).mapTo(HashSet()) { it.key }

        merged.keys shouldBe set
        unknown(result.merged) shouldBe unknown(s.remote)
        result.localChanges.remove.shouldBeEmpty()
    }

    private fun merge(base: PrefsPayload?, local: PrefsPayload, remote: PrefsPayload?) = PrefMerge.merge(
        base,
        local,
        remote,
    )

    private fun synced(payload: PrefsPayload) = PrefsPayload(payload.prefs.filter { it.key in SYNCED_PREF_KEYS })

    private fun unknown(payload: PrefsPayload) = PrefsPayload(payload.prefs.filter { it.key !in SYNCED_PREF_KEYS })

    private fun applyLocal(local: PrefsPayload, changes: PrefChangeSet): PrefsPayload {
        val values = local.prefs.associateTo(HashMap()) { it.key to it.value }
        for (pref in changes.set) values[pref.key] = pref.value
        for (key in changes.remove) values -= key
        return PrefsPayload(values.map { (key, value) -> PrefRecord(key, value) }).canonical()
    }
}
