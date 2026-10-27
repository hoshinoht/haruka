package haruka.storage

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import mihon.core.common.cache.TtlDiskCache
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

class TtlDiskCacheTrimTest {

    @TempDir
    lateinit var root: File

    private val directory get() = File(root, "cache")

    private val index get() = File(root, "index/cache.index")

    private var time = 1_000_000_000L

    private fun TestScope.cache() = TtlDiskCache(
        directory,
        index,
        1.days,
        backgroundScope,
        saveDelay = Duration.ZERO,
        dispatcher = StandardTestDispatcher(testScheduler),
        now = { time },
    )

    private suspend fun TtlDiskCache.put(key: String, bytes: Int) = put(key) { it.write(ByteArray(bytes)) }

    /** Entry file names and sizes the index lists. */
    private fun indexed() = index.readLines().drop(1).filter { it.isNotBlank() }.map { line ->
        line.split(' ').let { it[0] to it[2].toLong() }
    }

    private fun entryFiles() = directory.listFiles().orEmpty().map { it.name to it.length() }

    @Test
    fun `removes the oldest written entries first until under the limit`() = runTest {
        val cache = cache()
        cache.put("a", 100)
        time += 1_000
        cache.put("b", 100)
        time += 1_000
        cache.put("c", 100)
        time += 1_000
        cache.put("d", 100)
        // Rewriting moves an entry to the newest
        time += 1_000
        cache.put("a", 100)

        cache.trimToSize(250) shouldBe 2

        cache.contains("b") shouldBe false
        cache.contains("c") shouldBe false
        cache.contains("d") shouldBe true
        cache.contains("a") shouldBe true
        cache.totalSize() shouldBe 200
        entryFiles().size shouldBe 2
        indexed() shouldContainExactlyInAnyOrder entryFiles()
    }

    @Test
    fun `breaks write time ties in the order entries were written`() = runTest {
        val cache = cache()
        cache.put("first", 10)
        cache.put("second", 10)
        cache.put("third", 10)

        cache.trimToSize(20) shouldBe 1

        cache.contains("first") shouldBe false
        cache.contains("second") shouldBe true
        cache.contains("third") shouldBe true
    }

    @Test
    fun `does nothing when already within the limit`() = runTest {
        val cache = cache()
        cache.put("a", 100)
        cache.put("b", 100)

        cache.trimToSize(200) shouldBe 0
        cache.totalSize() shouldBe 200
        entryFiles().size shouldBe 2
    }

    @Test
    fun `trims to empty and keeps working`() = runTest {
        val cache = cache()
        cache.put("a", 100)
        cache.put("b", 100)

        cache.trimToSize(0) shouldBe 2
        cache.totalSize() shouldBe 0
        entryFiles().size shouldBe 0
        indexed().size shouldBe 0

        cache.put("c", 50)
        cache.totalSize() shouldBe 50
    }

    @Test
    fun `a reloaded cache sees the trimmed index`() = runTest {
        val cache = cache()
        cache.put("a", 100)
        time += 1_000
        cache.put("b", 100)
        cache.trimToSize(100) shouldBe 1

        val reloaded = cache()
        reloaded.totalSize() shouldBe 100
        reloaded.contains("a") shouldBe false
        reloaded.contains("b") shouldBe true
    }
}
