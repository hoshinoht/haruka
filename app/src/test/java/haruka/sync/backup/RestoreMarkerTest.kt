package haruka.sync.backup

import eu.kanade.tachiyomi.data.backup.create.creators.PreferenceBackupCreator
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.InMemoryPreferenceStore.InMemoryPreference
import tachiyomi.core.common.preference.PreferenceStore

class RestoreMarkerTest {

    private fun marker(stored: Long? = null) = RestoreMarker(
        InMemoryPreferenceStore(
            listOfNotNull(stored?.let { InMemoryPreference(RestoreMarker.KEY, it, RestoreMarker.NEVER) }).asSequence(),
        ),
    )

    @Test
    fun `no restore yet means no restore for the sync request`() {
        marker().lastRestoreAtMillis() shouldBe null
    }

    @Test
    fun `a finished restore is reported at its time`() {
        val marker = marker()

        marker.onRestoreFinished(nowMillis = 1_700_000_000_000)

        marker.lastRestoreAtMillis() shouldBe 1_700_000_000_000
    }

    @Test
    fun `the stored marker is read back`() {
        marker(stored = 1_700_000_000_000).lastRestoreAtMillis() shouldBe 1_700_000_000_000
    }

    @Test
    fun `a restore after the clock went back still moves the marker forward`() {
        // The engine records the marker as seen; a smaller value would be ignored and the restore missed.
        val marker = marker(stored = 1_700_000_000_000)

        marker.onRestoreFinished(nowMillis = 1_600_000_000_000)

        marker.lastRestoreAtMillis() shouldBe 1_700_000_000_001
    }

    @Test
    fun `a later restore moves the marker to its time`() {
        val marker = marker(stored = 1_700_000_000_000)

        marker.onRestoreFinished(nowMillis = 1_800_000_000_000)

        marker.lastRestoreAtMillis() shouldBe 1_800_000_000_000
    }

    @Test
    fun `a restore killed mid-way is recorded by its first progress report`() {
        val marker = marker(stored = 1_700_000_000_000)

        marker.onRestoreProgress(nowMillis = 1_800_000_000_000)

        marker.lastRestoreAtMillis() shouldBe 1_800_000_000_000
    }

    @Test
    fun `later progress reports of the same restore leave the marker alone`() {
        val marker = marker()

        marker.onRestoreProgress(nowMillis = 1_800_000_000_000)
        marker.onRestoreProgress(nowMillis = 1_800_000_000_500)
        marker.onRestoreProgress(nowMillis = 1_800_000_001_000)

        marker.lastRestoreAtMillis() shouldBe 1_800_000_000_000
    }

    @Test
    fun `the end of a restore moves the marker past its start`() {
        // A sync that ran mid-restore recorded the start as seen; the end must still force union mode again.
        val marker = marker()

        marker.onRestoreProgress(nowMillis = 1_800_000_000_000)
        marker.onRestoreFinished(nowMillis = 1_800_000_000_000)

        marker.lastRestoreAtMillis() shouldBe 1_800_000_000_001
    }

    @Test
    fun `the next restore is recorded at its start again`() {
        val marker = marker()

        marker.onRestoreProgress(nowMillis = 1_800_000_000_000)
        marker.onRestoreFinished(nowMillis = 1_800_000_010_000)
        marker.onRestoreProgress(nowMillis = 1_900_000_000_000)

        marker.lastRestoreAtMillis() shouldBe 1_900_000_000_000
    }

    @Test
    fun `Mihon backups never carry the marker, even with private settings included`() {
        val store = mockk<PreferenceStore> {
            every { getAll() } returns mapOf(
                RestoreMarker.KEY to 1_700_000_000_000L,
                "haruka_group_updates" to true,
            )
        }
        val creator = PreferenceBackupCreator(sourceManager = mockk(), preferenceStore = store)

        creator.createApp(includePrivatePreferences = true).map { it.key } shouldBe listOf("haruka_group_updates")
        creator.createApp(includePrivatePreferences = false).map { it.key } shouldBe listOf("haruka_group_updates")
    }
}
