package haruka.sync

import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import haruka.preference.HarukaPreferences
import haruka.sync.ui.SyncLastResult
import haruka.sync.work.SyncReason
import haruka.sync.work.SyncScheduler
import haruka.sync.work.SyncStatusStore
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.InMemoryPreferenceStore.InMemoryPreference
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

class SyncTriggersTest {

    // InMemoryPreferenceStore hands out a detached copy per lookup; the status written by the test's own
    // SyncStatusStore must be the one the triggers read.
    private val statusPreference = InMemoryPreference(Preference.appStateKey("haruka_sync_status"), null, "")
    private val inMemory = InMemoryPreferenceStore()
    private val store = object : PreferenceStore by inMemory {
        override fun getString(key: String, defaultValue: String): Preference<String> =
            if (key == statusPreference.key()) statusPreference else inMemory.getString(key, defaultValue)
    }
    private val preferences = HarukaPreferences(store)
    private val triggers = SyncTriggers(mockk(), preferences, store)
    private val workManager = mockk<WorkManager>(relaxed = true)

    private fun syncedAt(millis: Long) =
        SyncStatusStore(store).record(SyncLastResult.Synced(union = false, conflicts = 0), atMillis = millis)

    private fun verifyEnqueued(name: String, times: Int = 1): List<OneTimeWorkRequest> {
        val requests = mutableListOf<OneTimeWorkRequest>()
        verify(exactly = times) { workManager.enqueueUniqueWork(name, ExistingWorkPolicy.REPLACE, capture(requests)) }
        return requests
    }

    @Test
    fun `a completed chapter enqueues the debounced sync when automatic sync is on`() {
        preferences.syncEnabled.set(true)

        triggers.fire(SyncTrigger.CHAPTER_COMPLETED, workManager, nowMillis = 1_000)

        val request = verifyEnqueued(SyncScheduler.DEBOUNCED).single()
        request.workSpec.input.getString(SyncScheduler.REASON_KEY) shouldBe SyncReason.TRIGGER.name
        request.workSpec.initialDelay shouldBe SyncScheduler.DEBOUNCE_SECONDS * 1000
    }

    @Test
    fun `no trigger enqueues anything while automatic sync is off`() {
        for (trigger in SyncTrigger.entries) triggers.fire(trigger, workManager, nowMillis = 1_000)

        verify(exactly = 0) { workManager.enqueueUniqueWork(any<String>(), any(), any<OneTimeWorkRequest>()) }
    }

    @Test
    fun `app start syncs unless a sync was attempted in the last five minutes`() {
        preferences.syncEnabled.set(true)
        val now = 10 * SyncTriggers.START_INTERVAL_MILLIS

        triggers.fire(SyncTrigger.APP_START, workManager, nowMillis = now)
        verifyEnqueued(SyncScheduler.DEBOUNCED)

        syncedAt(now - SyncTriggers.START_INTERVAL_MILLIS + 1)
        triggers.fire(SyncTrigger.APP_START, workManager, nowMillis = now)
        verifyEnqueued(SyncScheduler.DEBOUNCED)

        syncedAt(now - SyncTriggers.START_INTERVAL_MILLIS)
        triggers.fire(SyncTrigger.APP_START, workManager, nowMillis = now)
        verifyEnqueued(SyncScheduler.DEBOUNCED, times = 2)
    }

    @Test
    fun `app stop syncs at once only after a chapter completed since the last attempt`() {
        preferences.syncEnabled.set(true)
        syncedAt(500)

        triggers.fire(SyncTrigger.APP_STOP, workManager, nowMillis = 1_000)
        verifyEnqueued(SyncScheduler.NOW, times = 0)

        triggers.fire(SyncTrigger.CHAPTER_COMPLETED, workManager, nowMillis = 2_000)
        triggers.fire(SyncTrigger.APP_STOP, workManager, nowMillis = 3_000)

        verify { workManager.cancelUniqueWork(SyncScheduler.DEBOUNCED) }
        val request = verifyEnqueued(SyncScheduler.NOW).single()
        request.workSpec.input.getString(SyncScheduler.REASON_KEY) shouldBe SyncReason.TRIGGER.name

        // The debounced or immediate sync ran after the chapter, so the next stop has nothing to send.
        syncedAt(4_000)
        triggers.fire(SyncTrigger.APP_STOP, workManager, nowMillis = 5_000)
        verifyEnqueued(SyncScheduler.NOW)
    }

    @Test
    fun `app stop with wifi only keeps the pending debounced sync`() {
        preferences.syncEnabled.set(true)
        preferences.syncWifiOnly.set(true)

        triggers.fire(SyncTrigger.CHAPTER_COMPLETED, workManager, nowMillis = 1_000)
        triggers.fire(SyncTrigger.APP_STOP, workManager, nowMillis = 2_000)

        verifyEnqueued(SyncScheduler.DEBOUNCED)
        verifyEnqueued(SyncScheduler.NOW, times = 0)
        verify(exactly = 0) { workManager.cancelUniqueWork(any()) }
    }

    @Test
    fun `a chapter completed while sync was off still counts once it is turned on`() {
        triggers.fire(SyncTrigger.CHAPTER_COMPLETED, workManager, nowMillis = 1_000)
        preferences.syncEnabled.set(true)

        triggers.fire(SyncTrigger.APP_STOP, workManager, nowMillis = 2_000)

        verifyEnqueued(SyncScheduler.NOW)
    }
}
