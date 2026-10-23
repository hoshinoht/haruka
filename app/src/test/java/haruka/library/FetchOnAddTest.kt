package haruka.library

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

class FetchOnAddTest {

    private val now = Instant.fromEpochMilliseconds(1_790_000_000_000)

    @Test
    fun `chapters stamped ahead of the clock still land before the added time, so Updates skips them`() {
        // Chapter sync stamps a 300-chapter list up to 300 ms past its start, which can run ahead of the clock.
        val fetched = (1..300).map { now + it.milliseconds }

        val added = addedAfterFirstFetch(now, fetched)!!

        fetched.all { it < added } shouldBe true
        added shouldBe now + 301.milliseconds
    }

    @Test
    fun `a list fetched before now is added at now`() {
        addedAfterFirstFetch(now, listOf(now - 5.milliseconds, now - 1.milliseconds)) shouldBe now
    }

    @Test
    fun `nothing fetched leaves the added time alone`() {
        addedAfterFirstFetch(now, emptyList()) shouldBe null
    }
}
