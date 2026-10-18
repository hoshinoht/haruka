package haruka.updateerrors

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class UpdateErrorStoreTest {

    @TempDir
    lateinit var dir: File

    // Same configuration as the app's Json binding.
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private val file get() = File(dir, "haruka/update_errors.json")

    private fun store() = UpdateErrorStore(file, json)

    private fun UpdateErrorStore.current() = runBlocking { errors.first() }

    private val timeout = UpdateError(1, "Alpha", 10, "Timeout")
    private val noChapters = UpdateError(2, "Beta", 20, "No chapters found")
    private val unknown = UpdateError(3, "Gamma", 10, null)

    @Test
    fun `errors survive a new store instance, including null messages`() {
        runBlocking { store().replace(listOf(timeout, noChapters, unknown)) }

        store().current() shouldBe listOf(timeout, noChapters, unknown)
    }

    @Test
    fun `a new run replaces the previous run's errors`() {
        val store = store()
        runBlocking {
            store.replace(listOf(timeout, noChapters))
            store.replace(emptyList())
        }
        store.current() shouldBe emptyList()

        runBlocking { store.replace(listOf(unknown)) }

        store.current() shouldBe listOf(unknown)
        store().current() shouldBe listOf(unknown)
    }

    @Test
    fun `missing or corrupt file reads as no errors`() {
        store().current() shouldBe emptyList()

        file.parentFile!!.mkdirs()
        file.writeText("{not json")

        store().current() shouldBe emptyList()
    }

    @Test
    fun `grouping puts the biggest group first and sorts entries by title`() {
        val delta = UpdateError(4, "delta", 30, "Timeout")
        val charlie = UpdateError(5, "Charlie", 30, "Timeout")

        groupByMessage(listOf(noChapters, delta, unknown, timeout, charlie)) shouldBe listOf(
            "Timeout" to listOf(timeout, charlie, delta),
            "No chapters found" to listOf(noChapters),
            null to listOf(unknown),
        )
    }

    @Test
    fun `grouping of no errors is empty`() {
        groupByMessage(emptyList()) shouldBe emptyList()
    }
}
