package haruka.chapter.dedup

import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.random.Random

class ChapterDeduperTest {

    private val deduper = ChapterDeduper(testCatalog)

    private fun winnerOf(
        vararg chapters: DedupChapter,
        options: DedupOptions = DedupOptions(),
        downloaded: Set<Long> = emptySet(),
    ): List<Long> {
        val result = deduper.dedupe(chapters.toList(), options) { it.id in downloaded }
        result.size shouldBe 1
        return result.single().winner.ids
    }

    @Test
    fun `downloaded beats official`() {
        winnerOf(chapter(1, 1.0, "VIZ"), chapter(2, 1.0, "Group A"), downloaded = setOf(2)) shouldBe listOf(2L)
    }

    @Test
    fun `a split copy counts as downloaded only when all parts are`() {
        val chapters = arrayOf(
            chapter(1, 1.0, "Official"),
            chapter(2, 1.1, "Group B"),
            chapter(3, 1.2, "Group B"),
            chapter(4, 1.3, "Group B"),
        )
        winnerOf(*chapters, downloaded = setOf(2, 3)) shouldBe listOf(1L)
        winnerOf(*chapters, downloaded = setOf(2, 3, 4)) shouldBe listOf(2L, 3L, 4L)
    }

    @Test
    fun `a whole chapter beats the same chapter split into newer parts`() {
        winnerOf(
            chapter(5, 21.0, "Official", name = "Chapter 21 -4", dateUpload = 50),
            chapter(4, 21.0, "Official", name = "Chapter 21 -3", dateUpload = 40),
            chapter(3, 21.0, "Official", name = "Chapter 21 -2", dateUpload = 30),
            chapter(2, 21.0, "Official", name = "Chapter 21 -1", dateUpload = 20),
            chapter(1, 21.0, "Official", name = "Chapter 21", dateUpload = 10),
        ) shouldBe listOf(1L)
    }

    @Test
    fun `official parts still beat an unofficial whole chapter`() {
        winnerOf(
            chapter(1, 7.0, "Group A"),
            chapter(2, 7.1, "Official"),
            chapter(3, 7.2, "Official"),
        ) shouldBe listOf(2L, 3L)
    }

    @Test
    fun `official beats doubtfully official`() {
        winnerOf(chapter(1, 1.0, "Official?"), chapter(2, 1.0, "Official")) shouldBe listOf(2L)
    }

    @Test
    fun `low quality loses to a plain copy, official or not`() {
        winnerOf(chapter(1, 1.0, "Official (low quality)"), chapter(2, 1.0, "mmscans")) shouldBe listOf(2L)
        winnerOf(chapter(1, 1.0, "(low quality)", dateUpload = 9), chapter(2, 1.0, "Group A")) shouldBe listOf(2L)
        winnerOf(chapter(1, 1.0, "Group A & mangaplus (low quality)"), chapter(2, 1.0, null)) shouldBe listOf(2L)
    }

    @Test
    fun `among low quality copies, official status still decides`() {
        winnerOf(
            chapter(1, 1.0, "(low quality)", dateUpload = 9),
            chapter(2, 1.0, "Official (low quality)"),
            chapter(3, 1.0, "Official?"),
        ) shouldBe listOf(3L)
        winnerOf(chapter(1, 1.0, "(low quality)", dateUpload = 9), chapter(2, 1.0, "Official (low quality)")) shouldBe
            listOf(2L)
    }

    @Test
    fun `a downloaded low quality copy wins only while DOWNLOADED is the first rule`() {
        val chapters = arrayOf(chapter(1, 1.0, "Official (low quality)"), chapter(2, 1.0, "mmscans"))
        winnerOf(*chapters, downloaded = setOf(1)) shouldBe listOf(1L)
        val officialFirst = DedupOptions(rules = listOf(DedupRule.OFFICIAL, DedupRule.DOWNLOADED))
        winnerOf(*chapters, options = officialFirst, downloaded = setOf(1)) shouldBe listOf(2L)
    }

    @Test
    fun `an official translation name counts as official on an aggregator source only`() {
        val chapters = arrayOf(
            chapter(1, 66.0, "Tyranitar", name = "Chapter 66: Ideals and Reality (10)", dateUpload = 9),
            chapter(2, 66.0, "Articuno", name = "Ch. 66 OFFICIAL TRANSLATION"),
        )
        winnerOf(*chapters, options = DedupOptions(sourceIsAggregator = true)) shouldBe listOf(2L)
        winnerOf(*chapters) shouldBe listOf(1L)
        val kagane = DedupOptions(sourceIsAggregator = testCatalog.isAggregatorSource("Kagane"))
        winnerOf(*chapters, options = kagane) shouldBe listOf(1L)
        val mangaBall = DedupOptions(sourceIsAggregator = testCatalog.isAggregatorSource("Manga Ball"))
        winnerOf(*chapters, options = mangaBall) shouldBe listOf(2L)
    }

    @Test
    fun `unofficial translation in a name is not official`() {
        val options = DedupOptions(sourceIsAggregator = true)
        winnerOf(
            chapter(1, 5.0, "Entei", name = "Chapter 5", dateUpload = 9),
            chapter(2, 5.0, "Articuno", name = "Ch. 5 unofficial translation"),
            options = options,
        ) shouldBe listOf(1L)
        isNamedOfficialTranslation("Ch. 5 Official Translations") shouldBe true
        isNamedOfficialTranslation("Ch. 5 (official translation)") shouldBe true
        isNamedOfficialTranslation("Ch. 5 un-official translation") shouldBe false
        isNamedOfficialTranslation("Ch. 5 officialtranslation") shouldBe false
        isNamedOfficialTranslation("Ch. 5 OFFICIAL") shouldBe false
    }

    @Test
    fun `a split copy is named official only when every part is`() {
        val options = DedupOptions(sourceIsAggregator = true)
        winnerOf(
            chapter(1, 72.0, "Entei", dateUpload = 9),
            chapter(2, 72.1, "Empoleon", name = "Ch. 72.1 OFFICIAL TRANSLATION"),
            chapter(3, 72.2, "Empoleon", name = "Ch. 72.2 SCANSLATION"),
            options = options,
        ) shouldBe listOf(1L)
    }

    @Test
    fun `doubtfully official beats user ranked`() {
        val options = DedupOptions(rankedScanlators = listOf("Group A"))
        winnerOf(chapter(1, 1.0, "Group A"), chapter(2, 1.0, "Official?"), options = options) shouldBe listOf(2L)
    }

    @Test
    fun `unofficial is not official`() {
        winnerOf(
            chapter(1, 1.0, "unofficial", dateUpload = 9),
            chapter(2, 1.0, "official", dateUpload = 1),
        ) shouldBe listOf(2L)
    }

    @Test
    fun `user ranked beats unranked and earlier rank wins`() {
        val options = DedupOptions(rankedScanlators = listOf("Group B", "Group A"))
        winnerOf(
            chapter(1, 1.0, "Group C", dateUpload = 9),
            chapter(2, 1.0, "Group A"),
            options = options,
        ) shouldBe listOf(2L)
        winnerOf(chapter(1, 1.0, "Group A"), chapter(2, 1.0, "Group B"), options = options) shouldBe listOf(2L)
    }

    @Test
    fun `user ranks match spelling variants and groups inside multi-group strings`() {
        val options = DedupOptions(rankedScanlators = listOf("Lua_Comic", "Magus Manga"))
        winnerOf(chapter(1, 1.0, "magusmanga"), chapter(2, 1.0, "Lua Comics"), options = options) shouldBe listOf(2L)
        winnerOf(chapter(1, 1.0, "Group X"), chapter(2, 1.0, "Group Y & MagusManga"), options = options) shouldBe
            listOf(2L)
        winnerOf(
            chapter(1, 1.0, "Group X"),
            chapter(2, 1.0, "Articuno (comick)"),
            options = options.copy(
                rankedScanlators = listOf("Articuno"),
            ),
        ) shouldBe listOf(2L)
    }

    @Test
    fun `among unranked, the newest upload wins`() {
        winnerOf(chapter(1, 1.0, "Group A", dateUpload = 5), chapter(2, 1.0, null, dateUpload = 7)) shouldBe
            listOf(2L)
    }

    @Test
    fun `equal uploads fall back to the lowest source order`() {
        winnerOf(chapter(1, 1.0, "Group A", sourceOrder = 4), chapter(2, 1.0, "Group B", sourceOrder = 3)) shouldBe
            listOf(2L)
    }

    @Test
    fun `equal source order falls back to the lowest id`() {
        winnerOf(chapter(5, 1.0, "Group A", sourceOrder = 0), chapter(3, 1.0, "Group B", sourceOrder = 0)) shouldBe
            listOf(3L)
    }

    @Test
    fun `a multi-group string with an official group is official`() {
        winnerOf(chapter(1, 1.0, "Group Y", dateUpload = 9), chapter(2, 1.0, "Group X & VIZ")) shouldBe listOf(2L)
    }

    @Test
    fun `an official source makes every copy official so later rules decide`() {
        val options = DedupOptions(rankedScanlators = listOf("Group B"), sourceIsOfficial = true)
        winnerOf(chapter(1, 1.0, "VIZ"), chapter(2, 1.0, "Group B"), options = options) shouldBe listOf(2L)
    }

    @Test
    fun `rule order is configurable`() {
        val options = DedupOptions(
            rules = listOf(DedupRule.USER_RANK, DedupRule.OFFICIAL),
            rankedScanlators = listOf("Group A"),
        )
        winnerOf(chapter(1, 1.0, "VIZ"), chapter(2, 1.0, "Group A"), options = options) shouldBe listOf(2L)
        winnerOf(chapter(1, 1.0, "VIZ"), chapter(2, 1.0, "Group A")) shouldBe listOf(1L)
    }

    @Test
    fun `others are ordered best first and logical order follows the input`() {
        val result = deduper.dedupe(
            listOf(
                chapter(1, 2.0, "Group A", dateUpload = 1),
                chapter(2, 1.0, "Group A", dateUpload = 1),
                chapter(3, 2.0, "Group B", dateUpload = 3),
                chapter(4, 2.0, "Official"),
                chapter(5, -1.0, "Group A"),
            ),
        )
        result.map { it.logical.number } shouldBe listOf(2.0, 1.0, -1.0)
        result[0].winner.ids shouldBe listOf(4L)
        result[0].others.map { it.ids } shouldBe listOf(listOf(3L), listOf(1L))
        result[2].winner.ids shouldBe listOf(5L)
        result[2].others shouldBe emptyList()
    }

    @Test
    fun `generated libraries keep every chapter, one winner each, independent of input order`() {
        val random = Random(20261019)
        repeat(300) {
            val chapters = generateChapters(random)
            val options = DedupOptions(
                rankedScanlators = listOf("Group B", "Lua Comic"),
                mergeSplitChapters = random.nextBoolean(),
                sourceIsOfficial = random.nextInt(10) == 0,
                sourceIsAggregator = random.nextBoolean(),
            )
            val isDownloaded = { chapter: DedupChapter -> chapter.id % 3 == 0L }
            val result = deduper.dedupe(chapters, options, isDownloaded)

            result.forEach { deduped ->
                deduped.logical.copies.size shouldBe deduped.others.size + 1
                deduped.logical.copies.toSet() shouldBe (deduped.others + deduped.winner).toSet()
            }
            // Collapsed repeat uploads stay with their copy, so every chapter is still returned exactly once.
            val returned = result.flatMap { deduped ->
                (deduped.others + deduped.winner).flatMap { copy -> copy.ids + copy.duplicates.map { it.id } }
            }
            returned.sorted() shouldBe chapters.map { it.id }.sorted()

            val shuffled = deduper.dedupe(chapters.shuffled(random), options, isDownloaded)
            shuffled.describe() shouldBe result.describe()
        }
    }

    @Test
    fun `scale - logs timing for 3,000 chapters x 4 groups`() {
        val chapters = benchmarkChapters(3_000)
        repeat(WARMUP) { deduper.dedupe(chapters, benchmarkOptions, benchmarkDownloaded) }
        val millis = medianMillis { deduper.dedupe(chapters, benchmarkOptions, benchmarkDownloaded) }
        println("ChapterDeduper: ${chapters.size} chapters (3,000 x 4 groups) deduped in median $millis ms")
    }

    @Test
    fun `scale - 1,005 chapters x 4 groups dedupe in under 50 ms`() {
        val chapters = benchmarkChapters(1_005)
        repeat(WARMUP) { deduper.dedupe(chapters, benchmarkOptions, benchmarkDownloaded) }
        val millis = medianMillis { deduper.dedupe(chapters, benchmarkOptions, benchmarkDownloaded) }
        println("ChapterDeduper: ${chapters.size} chapters (1,005 x 4 groups) deduped in median $millis ms")
        millis shouldBeLessThan 50.0
    }

    /** Sorted, order-free rendering of a result: number, part, kind, winner and other ids per logical chapter. */
    private fun List<DedupedChapter>.describe(): List<String> = map { deduped ->
        val logical = deduped.logical
        "${logical.number}|${logical.part}|${logical.kind}|${deduped.winner.describe()}|" +
            deduped.others.map { it.describe() }
    }.sorted()

    private fun ChapterCopy.describe() = "$ids${duplicates.map { it.id }.sorted()}"

    private fun generateChapters(random: Random): List<DedupChapter> {
        val scanlators = listOf(
            null,
            "Group A",
            "Group B",
            "Official",
            "Official?",
            "Official (low quality)",
            "(low quality)",
            "unofficial",
            "Group C & VIZ",
            "Lua Comics",
        )
        val names = listOf("Chapter %s", "Ch. %s OFFICIAL TRANSLATION", "Chapter %s - Side Story", "Spin-Off #%s")
        val ids = (1L..1_000L).shuffled(random).iterator()
        val chapters = ArrayList<DedupChapter>()
        repeat(random.nextInt(0, 120)) {
            val scanlator = scanlators.random(random)
            val whole = random.nextInt(1, 15)
            val date = random.nextLong(0, 5)
            val order = random.nextLong(0, 50)
            when (random.nextInt(6)) {
                0 -> chapters += chapter(ids.next(), -1.0, scanlator, dateUpload = date, sourceOrder = order)
                1 -> repeat(random.nextInt(2, 5)) { part ->
                    chapters += chapter(ids.next(), "$whole.${part + 1}".toDouble(), scanlator, dateUpload = date)
                }
                2 -> chapters += chapter(ids.next(), "$whole.5".toDouble(), scanlator, dateUpload = date)
                3 -> chapters += chapter(
                    ids.next(),
                    "$whole.1".toDouble(),
                    scanlator,
                    name = "Chapter $whole.10",
                    dateUpload = date,
                )
                else -> chapters += chapter(
                    ids.next(),
                    whole.toDouble(),
                    scanlator,
                    name = names.random(random).format(whole),
                    dateUpload = date,
                    sourceOrder = order,
                )
            }
        }
        return chapters
    }

    private val benchmarkOptions = DedupOptions(rankedScanlators = listOf("Group B & Group C", "Lua Comic"))
    private val benchmarkDownloaded = { chapter: DedupChapter -> chapter.id % 7 == 0L }

    /** Every group has every chapter; one group splits every tenth chapter into three parts. */
    private fun benchmarkChapters(count: Int): List<DedupChapter> {
        val groups = listOf("Official", "Group A", "Group B & Group C", "Lua Comics")
        var id = 0L
        val chapters = ArrayList<DedupChapter>(count * groups.size + count)
        for (number in 1..count) {
            groups.forEachIndexed { group, scanlator ->
                val date = number * 1_000L + group
                val order = (count - number).toLong()
                if (group == 3 && number % 10 == 0) {
                    for (part in 1..3) {
                        chapters +=
                            chapter(++id, "$number.$part".toDouble(), scanlator, dateUpload = date, sourceOrder = order)
                    }
                } else {
                    chapters += chapter(++id, number.toDouble(), scanlator, dateUpload = date, sourceOrder = order)
                }
            }
        }
        return chapters
    }

    private inline fun medianMillis(block: () -> Unit): Double {
        val samples = DoubleArray(RUNS) {
            val start = System.nanoTime()
            block()
            (System.nanoTime() - start) / 1_000_000.0
        }
        samples.sort()
        return samples[RUNS / 2]
    }

    private companion object {
        const val WARMUP = 10
        const val RUNS = 11
    }
}
