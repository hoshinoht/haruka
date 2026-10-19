package haruka.library.shelves

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import eu.kanade.tachiyomi.ui.library.LibraryItem
import haruka.i18n.HR
import haruka.library.ChapterCountSection
import haruka.library.LetterSection
import haruka.library.MonthSection
import haruka.library.OtherScriptSection
import haruka.library.RecencySection
import haruka.library.SectionKey
import haruka.library.SectionSort
import haruka.library.ShelfInput
import haruka.library.SymbolSection
import haruka.library.UnknownDateSection
import haruka.library.UnreadSection
import tachiyomi.domain.library.model.LibrarySort
import tachiyomi.presentation.core.i18n.stringResource
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

// The fields LibraryViewModel.applySort reads, so the buckets follow the order the list is in.
internal fun LibraryItem.toShelfInput(): ShelfInput {
    val libraryManga = libraryManga
    val manga = libraryManga.manga
    return ShelfInput(
        title = manga.title,
        lastRead = libraryManga.lastRead?.toEpochMilliseconds(),
        lastUpdate = manga.lastUpdate?.toEpochMilliseconds(),
        latestUpload = libraryManga.latestUpload?.toEpochMilliseconds(),
        chapterFetchedAt = libraryManga.chapterFetchedAt?.toEpochMilliseconds(),
        favoriteAt = manga.favoriteAt?.toEpochMilliseconds(),
        unreadCount = libraryManga.unreadCount,
        totalChapters = libraryManga.totalChapters,
    )
}

// Tracker scores never reach the library screen, so that sort has no dividers yet.
internal fun LibrarySort.Type.toSectionSort(): SectionSort = when (this) {
    LibrarySort.Type.Alphabetical -> SectionSort.Alphabetical
    LibrarySort.Type.LastRead -> SectionSort.LastRead
    LibrarySort.Type.LastUpdate -> SectionSort.LastUpdate
    LibrarySort.Type.LatestChapter -> SectionSort.LatestChapter
    LibrarySort.Type.ChapterFetchDate -> SectionSort.ChapterFetchDate
    LibrarySort.Type.DateAdded -> SectionSort.DateAdded
    LibrarySort.Type.UnreadCount -> SectionSort.UnreadCount
    LibrarySort.Type.TotalChapters -> SectionSort.TotalChapters
    LibrarySort.Type.TrackerMean, LibrarySort.Type.Random -> SectionSort.None
}

/** Month and year in the locale's own order, e.g. "October 2026" or "2026年10月". */
@Composable
internal fun rememberMonthFormatter(): DateTimeFormatter = remember {
    val locale = Locale.getDefault()
    DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale, "MMMMy"), locale)
}

@Composable
internal fun SectionKey.label(monthFormatter: DateTimeFormatter): String = when (this) {
    RecencySection.Today -> stringResource(HR.strings.library_section_today)
    RecencySection.ThisWeek -> stringResource(HR.strings.library_section_this_week)
    RecencySection.ThisMonth -> stringResource(HR.strings.library_section_this_month)
    RecencySection.EarlierThisYear -> stringResource(HR.strings.library_section_earlier_this_year)
    RecencySection.Older -> stringResource(HR.strings.library_section_older)
    RecencySection.NeverRead -> stringResource(HR.strings.library_section_never_read)
    is MonthSection -> remember(this, monthFormatter) { monthFormatter.format(YearMonth.of(year, month)) }
    UnknownDateSection -> stringResource(HR.strings.library_section_unknown_date)
    SymbolSection -> "#"
    is LetterSection -> letter.toString()
    OtherScriptSection -> stringResource(HR.strings.library_section_other_scripts)
    UnreadSection.TenOrMore -> stringResource(HR.strings.library_section_unread_ten_or_more)
    UnreadSection.OneToNine -> stringResource(HR.strings.library_section_unread_one_to_nine)
    UnreadSection.CaughtUp -> stringResource(HR.strings.library_section_caught_up)
    ChapterCountSection.HundredOrMore -> stringResource(HR.strings.library_section_chapters_hundred_or_more)
    ChapterCountSection.FiftyToNinetyNine -> stringResource(HR.strings.library_section_chapters_fifty_to_ninety_nine)
    ChapterCountSection.TenToFortyNine -> stringResource(HR.strings.library_section_chapters_ten_to_forty_nine)
    ChapterCountSection.UnderTen -> stringResource(HR.strings.library_section_chapters_under_ten)
}
