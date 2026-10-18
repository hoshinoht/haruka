package haruka.related

import eu.kanade.tachiyomi.source.model.SManga
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class RelatedMangaSourceTest {

    private val manga = sManga("/current")

    @Test
    fun `suspending fetch is bridged when the source supports related titles`() = runTest {
        RelatedMangaSource.fetch(SupportingSource(), manga).map { it.url } shouldBe listOf("/current/related")
    }

    @Test
    fun `nothing is fetched when the source opts out`() = runTest {
        val source = OptedOutSource()

        RelatedMangaSource.fetch(source, manga) shouldBe emptyList()
        source.fetched shouldBe false
    }

    @Test
    fun `sources without the members yield nothing`() = runTest {
        RelatedMangaSource.fetch(FlagOnlySource(), manga) shouldBe emptyList()
        RelatedMangaSource.fetch(Any(), manga) shouldBe emptyList()
    }

    @Test
    fun `a throwing fetch yields nothing`() = runTest {
        RelatedMangaSource.fetch(ThrowingSource(), manga) shouldBe emptyList()
        RelatedMangaSource.fetch(EagerThrowingSource(), manga) shouldBe emptyList()
    }

    @Test
    fun `a fetch that never finishes is abandoned`() = runTest {
        RelatedMangaSource.fetch(HangingSource(), manga) shouldBe emptyList()
    }
}

private fun sManga(url: String) = SManga.create().apply {
    this.url = url
    title = url
}

class SupportingSource {
    val supportsRelatedMangas = true

    suspend fun fetchRelatedMangaList(manga: SManga): List<SManga> {
        delay(1)
        return listOf(sManga("${manga.url}/related"))
    }
}

class OptedOutSource {
    val supportsRelatedMangas = false
    var fetched = false

    @Suppress("RedundantSuspendModifier")
    suspend fun fetchRelatedMangaList(manga: SManga): List<SManga> {
        fetched = true
        return listOf(sManga("/other"))
    }
}

class FlagOnlySource {
    val supportsRelatedMangas = true
}

class ThrowingSource {
    val supportsRelatedMangas = true

    suspend fun fetchRelatedMangaList(manga: SManga): List<SManga> {
        delay(1)
        throw IllegalStateException("site changed")
    }
}

class EagerThrowingSource {
    val supportsRelatedMangas = true

    @Suppress("RedundantSuspendModifier")
    suspend fun fetchRelatedMangaList(manga: SManga): List<SManga> = throw UnsupportedOperationException()
}

class HangingSource {
    val supportsRelatedMangas = true

    suspend fun fetchRelatedMangaList(manga: SManga): List<SManga> = awaitCancellation()
}
