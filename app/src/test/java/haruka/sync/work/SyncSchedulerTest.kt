package haruka.sync.work

import androidx.work.NetworkType
import haruka.preference.HarukaPreferences
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import java.util.concurrent.TimeUnit

class SyncSchedulerTest {

    private val schedule = SyncSchedule(enabled = true, intervalHours = 6, wifiOnly = false, chargingOnly = false)

    @Test
    fun `periodic sync uses any network and no charger by default`() {
        val constraints = SyncScheduler.periodicRequest(schedule).shouldNotBeNull().workSpec.constraints

        constraints.requiredNetworkType shouldBe NetworkType.CONNECTED
        constraints.requiresCharging() shouldBe false
    }

    @Test
    fun `wifi only and charging only map to unmetered and charging on the periodic sync`() {
        val request = SyncScheduler.periodicRequest(schedule.copy(wifiOnly = true, chargingOnly = true))

        val constraints = request.shouldNotBeNull().workSpec.constraints
        constraints.requiredNetworkType shouldBe NetworkType.UNMETERED
        constraints.requiresCharging() shouldBe true
    }

    @Test
    fun `periodic sync repeats at the chosen interval with its tag`() {
        val request = SyncScheduler.periodicRequest(schedule.copy(intervalHours = 12)).shouldNotBeNull()

        request.workSpec.intervalDuration shouldBe TimeUnit.HOURS.toMillis(12)
        request.tags.contains(SyncScheduler.TAG) shouldBe true
        request.workSpec.input.getString(SyncScheduler.REASON_KEY) shouldBe SyncReason.PERIODIC.name
    }

    @Test
    fun `no periodic sync when the interval is off or sync is disabled`() {
        SyncScheduler.periodicRequest(schedule.copy(intervalHours = 0)).shouldBeNull()
        SyncScheduler.periodicRequest(schedule.copy(enabled = false)).shouldBeNull()
    }

    @Test
    fun `debounced triggers respect wifi only but never wait for a charger`() {
        val request = SyncScheduler.debouncedRequest(schedule.copy(wifiOnly = true, chargingOnly = true))

        request.workSpec.constraints.requiredNetworkType shouldBe NetworkType.UNMETERED
        request.workSpec.constraints.requiresCharging() shouldBe false
        request.workSpec.initialDelay shouldBe TimeUnit.SECONDS.toMillis(SyncScheduler.DEBOUNCE_SECONDS)
        request.workSpec.input.getString(SyncScheduler.REASON_KEY) shouldBe SyncReason.TRIGGER.name
    }

    @Test
    fun `sync now is expedited on any network and ignores wifi and charging options`() {
        val request = SyncScheduler.nowRequest(SyncReason.MANUAL)

        request.workSpec.expedited shouldBe true
        request.workSpec.constraints.requiredNetworkType shouldBe NetworkType.CONNECTED
        request.workSpec.constraints.requiresCharging() shouldBe false
        request.workSpec.initialDelay shouldBe 0L
        request.tags.contains(SyncScheduler.TAG) shouldBe true
        request.workSpec.input.getString(SyncScheduler.REASON_KEY) shouldBe SyncReason.MANUAL.name
    }

    @Test
    fun `schedule is read from the sync preferences`() {
        val preferences = HarukaPreferences(InMemoryPreferenceStore())

        SyncSchedule.of(preferences) shouldBe
            SyncSchedule(enabled = false, intervalHours = 6, wifiOnly = false, chargingOnly = false)

        preferences.syncEnabled.set(true)
        preferences.syncIntervalHours.set(1)
        preferences.syncWifiOnly.set(true)
        preferences.syncChargingOnly.set(true)

        SyncSchedule.of(preferences) shouldBe
            SyncSchedule(enabled = true, intervalHours = 1, wifiOnly = true, chargingOnly = true)
    }
}
