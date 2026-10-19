package haruka.library

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.text.Collator
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

class SortSectionsTest {

    private val zone = ZoneId.of("Asia/Tokyo")
    private val collator = Collator.getInstance(Locale.ENGLISH).apply { strength = Collator.PRIMARY }
    private val titleOrder = Comparator<String> { a, b -> collator.compare(a, b) }

    // Tuesday 2026-10-20 15:00 in Tokyo
    private val now = at(2026, 10, 20, 15, 0)
    private val sections = SortSections(now, zone, titleOrder)

    private fun at(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): Instant =
        LocalDateTime.of(year, month, day, hour, minute).atZone(zone).toInstant()

    private fun millis(instant: Instant) = instant.toEpochMilli()

    private fun entry(
        title: String = "Title",
        lastRead: Long? = null,
        lastUpdate: Long? = null,
        latestUpload: Long? = null,
        chapterFetchedAt: Long? = null,
        favoriteAt: Long? = null,
        unreadCount: Long = 0,
        totalChapters: Long = 0,
    ) = ShelfInput(title, lastRead, lastUpdate, latestUpload, chapterFetchedAt, favoriteAt, unreadCount, totalChapters)

    private fun dated(sort: SectionSort, time: Long?) = when (sort) {
        SectionSort.LastRead -> entry(lastRead = time)
        SectionSort.LastUpdate -> entry(lastUpdate = time)
        SectionSort.LatestChapter -> entry(latestUpload = time)
        SectionSort.ChapterFetchDate -> entry(chapterFetchedAt = time)
        SectionSort.DateAdded -> entry(favoriteAt = time)
        else -> error("Not a date sort: $sort")
    }

    private val day = 24L * 60 * 60 * 1000
    private val startOfToday = millis(at(2026, 10, 20))
    private val startOfYear = millis(at(2026, 1, 1))

    @Test
    fun `last read buckets split at midnight, day 7, day 30 and the start of the year`() {
        fun key(time: Long?) = sections.keyOf(SectionSort.LastRead, dated(SectionSort.LastRead, time))

        key(millis(now)) shouldBe RecencySection.Today
        key(startOfToday) shouldBe RecencySection.Today
        key(startOfToday - 1) shouldBe RecencySection.ThisWeek
        key(millis(now) - 7 * day + 1) shouldBe RecencySection.ThisWeek
        key(millis(now) - 7 * day) shouldBe RecencySection.ThisMonth
        key(millis(now) - 30 * day + 1) shouldBe RecencySection.ThisMonth
        key(millis(now) - 30 * day) shouldBe RecencySection.EarlierThisYear
        key(startOfYear) shouldBe RecencySection.EarlierThisYear
        key(startOfYear - 1) shouldBe RecencySection.Older
        key(0) shouldBe RecencySection.Older
        key(null) shouldBe RecencySection.NeverRead
    }

    @Test
    fun `times after now count as today`() {
        sections.keyOf(SectionSort.LastRead, entry(lastRead = millis(now) + 3 * day)) shouldBe RecencySection.Today
        sections.keyOf(SectionSort.LastUpdate, entry(lastUpdate = millis(now) + day)) shouldBe RecencySection.Today
    }

    @Test
    fun `update dates use the same buckets without earlier this year, and null is older`() {
        for (sort in listOf(SectionSort.LastUpdate, SectionSort.LatestChapter, SectionSort.ChapterFetchDate)) {
            fun key(time: Long?) = sections.keyOf(sort, dated(sort, time))

            key(startOfToday) shouldBe RecencySection.Today
            key(startOfToday - 1) shouldBe RecencySection.ThisWeek
            key(millis(now) - 7 * day + 1) shouldBe RecencySection.ThisWeek
            key(millis(now) - 7 * day) shouldBe RecencySection.ThisMonth
            key(millis(now) - 30 * day + 1) shouldBe RecencySection.ThisMonth
            key(millis(now) - 30 * day) shouldBe RecencySection.Older
            key(startOfYear) shouldBe RecencySection.Older
            key(null) shouldBe RecencySection.Older
        }
    }

    @Test
    fun `in early January the last 30 days reach into last year and earlier this year is skipped`() {
        val january = SortSections(at(2026, 1, 5, 9, 0), zone, titleOrder)
        fun key(time: Instant) = january.keyOf(SectionSort.LastRead, entry(lastRead = millis(time)))

        key(at(2026, 1, 5)) shouldBe RecencySection.Today
        key(at(2025, 12, 31, 23, 59)) shouldBe RecencySection.ThisWeek
        key(at(2025, 12, 20)) shouldBe RecencySection.ThisMonth
        key(at(2025, 11, 20)) shouldBe RecencySection.Older
        key(at(2026, 1, 4, 23, 59)) shouldBe RecencySection.ThisWeek
    }

    @Test
    fun `calendar days and years follow the zone`() {
        // 2026-10-20 00:30 in Tokyo is still 2026-10-19 in UTC
        val instant = at(2026, 10, 20, 0, 30)
        val utc = SortSections(at(2026, 10, 20, 15, 0), ZoneId.of("UTC"), titleOrder)
        sections.keyOf(SectionSort.LastRead, entry(lastRead = millis(instant))) shouldBe RecencySection.Today
        utc.keyOf(SectionSort.LastRead, entry(lastRead = millis(instant))) shouldBe RecencySection.ThisWeek

        // 2026-01-01 00:30 in Tokyo is 2025-12-31 in UTC
        val newYear = millis(at(2026, 1, 1, 0, 30))
        sections.keyOf(SectionSort.DateAdded, entry(favoriteAt = newYear)) shouldBe MonthSection(2026, 1)
        utc.keyOf(SectionSort.DateAdded, entry(favoriteAt = newYear)) shouldBe MonthSection(2025, 12)
    }

    @Test
    fun `date added groups by local month, and unset dates are unknown`() {
        fun key(time: Long?) = sections.keyOf(SectionSort.DateAdded, entry(favoriteAt = time))

        key(millis(at(2026, 10, 1))) shouldBe MonthSection(2026, 10)
        key(millis(at(2026, 10, 1)) - 1) shouldBe MonthSection(2026, 9)
        key(millis(at(2026, 1, 1))) shouldBe MonthSection(2026, 1)
        key(millis(at(2026, 1, 1)) - 1) shouldBe MonthSection(2025, 12)
        key(1) shouldBe MonthSection(1970, 1)
        key(0) shouldBe UnknownDateSection
        key(-5) shouldBe UnknownDateSection
        key(null) shouldBe UnknownDateSection
    }

    @Test
    fun `unread count buckets`() {
        fun key(unread: Long) = sections.keyOf(SectionSort.UnreadCount, entry(unreadCount = unread))

        key(0) shouldBe UnreadSection.CaughtUp
        key(1) shouldBe UnreadSection.OneToNine
        key(9) shouldBe UnreadSection.OneToNine
        key(10) shouldBe UnreadSection.TenOrMore
        key(250) shouldBe UnreadSection.TenOrMore
    }

    @Test
    fun `total chapter buckets`() {
        fun key(total: Long) = sections.keyOf(SectionSort.TotalChapters, entry(totalChapters = total))

        key(0) shouldBe ChapterCountSection.UnderTen
        key(9) shouldBe ChapterCountSection.UnderTen
        key(10) shouldBe ChapterCountSection.TenToFortyNine
        key(49) shouldBe ChapterCountSection.TenToFortyNine
        key(50) shouldBe ChapterCountSection.FiftyToNinetyNine
        key(99) shouldBe ChapterCountSection.FiftyToNinetyNine
        key(100) shouldBe ChapterCountSection.HundredOrMore
    }

    @Test
    fun `alphabetical buckets follow the collator`() {
        fun key(title: String) = sections.keyOf(SectionSort.Alphabetical, entry(title = title))

        key("") shouldBe SymbolSection
        key("!Exclaim") shouldBe SymbolSection
        key("#hashtag") shouldBe SymbolSection
        key("123 Go") shouldBe SymbolSection
        key("apple") shouldBe LetterSection('A')
        key("Ångström") shouldBe LetterSection('A')
        key("Æsir") shouldBe LetterSection('A')
        key("Élan") shouldBe LetterSection('E')
        key("ella") shouldBe LetterSection('E')
        key("Zebra") shouldBe LetterSection('Z')
        key("Žižka") shouldBe LetterSection('Z')
        key("zz top") shouldBe LetterSection('Z')
        key("Привет") shouldBe OtherScriptSection
        key("Ωmega") shouldBe OtherScriptSection
        key("日本の物語") shouldBe OtherScriptSection
        key("ひらがな") shouldBe OtherScriptSection
        key("カタカナ") shouldBe OtherScriptSection
        key("한국어 제목") shouldBe OtherScriptSection
    }

    @Test
    fun `random and tracker sorts have no sections`() {
        sections.sections(SectionSort.None, listOf(entry(), entry())).shouldBeEmpty()
        sections.sections(SectionSort.LastRead, emptyList()).shouldBeEmpty()
    }

    @Test
    fun `sections are contiguous runs in list order`() {
        val list = listOf(
            entry(unreadCount = 12),
            entry(unreadCount = 10),
            entry(unreadCount = 3),
            entry(unreadCount = 0),
            entry(unreadCount = 0),
        )
        sections.sections(SectionSort.UnreadCount, list) shouldContainExactly listOf(
            Section(UnreadSection.TenOrMore, 0, 2),
            Section(UnreadSection.OneToNine, 2, 1),
            Section(UnreadSection.CaughtUp, 3, 2),
        )
    }

    // The sorted lists below are built with the comparators of upstream's LibraryViewModel.applySort: compareValues
    // (nulls first), reversed when descending, ties broken by title; unread entries always before caught-up ones.

    private val library: List<ShelfInput> = run {
        val times = listOf(
            null,
            0L,
            millis(at(2019, 3, 2)),
            millis(at(2025, 12, 31, 23, 59)),
            startOfYear - 1,
            startOfYear,
            millis(at(2026, 6, 15)),
            millis(now) - 30 * day,
            millis(now) - 30 * day + 1,
            millis(now) - 9 * day,
            millis(now) - 7 * day,
            millis(now) - 7 * day + 1,
            millis(now) - 2 * day,
            startOfToday - 1,
            startOfToday,
            millis(now),
            millis(now) + day,
            -1L,
        )
        val titles = listOf(
            "123 Go", "!Exclaim", "#hashtag", "(Bracketed)", "apple", "Ångström", "Banana", "Élan", "ella",
            "Mango", "Zebra", "Žižka", "日本の物語", "ひらがな", "カタカナ", "한국어 제목", "Привет", "Ωmega",
        )
        titles.mapIndexed { index, title ->
            val time = times[index % times.size]
            entry(
                title = title,
                lastRead = time,
                lastUpdate = times[(index + 3) % times.size],
                latestUpload = times[(index + 5) % times.size],
                chapterFetchedAt = times[(index + 7) % times.size],
                favoriteAt = times[(index + 11) % times.size],
                unreadCount = listOf(0L, 1, 9, 10, 42, 0, 3)[index % 7],
                totalChapters = listOf(0L, 9, 10, 49, 50, 99, 100, 1200)[index % 8],
            )
        }
    }

    private fun comparator(sort: SectionSort, ascending: Boolean): Comparator<ShelfInput> {
        val byTitle = Comparator<ShelfInput> { a, b -> collator.compare(a.title, b.title) }
        val primary: Comparator<ShelfInput> = when (sort) {
            SectionSort.Alphabetical -> byTitle
            SectionSort.LastRead -> compareBy { it.lastRead }
            SectionSort.LastUpdate -> compareBy { it.lastUpdate }
            SectionSort.LatestChapter -> compareBy { it.latestUpload }
            SectionSort.ChapterFetchDate -> compareBy { it.chapterFetchedAt }
            SectionSort.DateAdded -> compareBy { it.favoriteAt }
            SectionSort.TotalChapters -> compareBy { it.totalChapters }
            SectionSort.UnreadCount -> Comparator { a, b ->
                when {
                    a.unreadCount == b.unreadCount -> 0
                    a.unreadCount == 0L -> if (ascending) 1 else -1
                    b.unreadCount == 0L -> if (ascending) -1 else 1
                    else -> a.unreadCount.compareTo(b.unreadCount)
                }
            }
            SectionSort.None -> error("unsorted")
        }
        return (if (ascending) primary else primary.reversed()).then(byTitle)
    }

    private val sorts = SectionSort.entries - SectionSort.None

    @Test
    fun `every sort yields each label once and covers the list in both directions`() {
        for (sort in sorts) {
            for (ascending in listOf(true, false)) {
                val sorted = library.sortedWith(comparator(sort, ascending))
                val result = sections.sections(sort, sorted)

                withClue("$sort ascending=$ascending") {
                    val keys = result.map { it.key }
                    keys.distinct() shouldBe keys
                    result.sumOf { it.count } shouldBe sorted.size
                    result.zipWithNext().forEach { (a, b) -> b.start shouldBe a.start + a.count }
                    result.forEach { section ->
                        sorted.subList(section.start, section.start + section.count)
                            .forEach { sections.keyOf(sort, it) shouldBe section.key }
                    }
                }
            }
        }
    }

    @Test
    fun `descending reverses the groups, except caught up which stays last`() {
        for (sort in sorts) {
            val ascending = sections.sections(sort, library.sortedWith(comparator(sort, true))).map { it.key }
            val descending = sections.sections(sort, library.sortedWith(comparator(sort, false))).map { it.key }
            if (sort == SectionSort.UnreadCount) {
                ascending shouldContainExactly listOf(
                    UnreadSection.OneToNine,
                    UnreadSection.TenOrMore,
                    UnreadSection.CaughtUp,
                )
                descending shouldContainExactly listOf(
                    UnreadSection.TenOrMore,
                    UnreadSection.OneToNine,
                    UnreadSection.CaughtUp,
                )
            } else {
                descending shouldContainExactly ascending.reversed()
            }
        }
    }

    @Test
    fun `mixed scripts sort into symbol, letter and other groups`() {
        val sorted = library.sortedWith(comparator(SectionSort.Alphabetical, true))
        sections.sections(SectionSort.Alphabetical, sorted).map { it.key } shouldContainExactly listOf(
            SymbolSection,
            LetterSection('A'),
            LetterSection('B'),
            LetterSection('E'),
            LetterSection('M'),
            LetterSection('Z'),
            OtherScriptSection,
        )
    }

    @Test
    fun `last read groups run from never read to today`() {
        val sorted = library.sortedWith(comparator(SectionSort.LastRead, true))
        sections.sections(SectionSort.LastRead, sorted).map { it.key } shouldContainExactly listOf(
            RecencySection.NeverRead,
            RecencySection.Older,
            RecencySection.EarlierThisYear,
            RecencySection.ThisMonth,
            RecencySection.ThisWeek,
            RecencySection.Today,
        )
    }
}
