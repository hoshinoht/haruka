package haruka.storage

import haruka.chapter.dedup.DedupChapter
import haruka.chapter.dedup.groupLogicalChapters
import java.util.TreeMap

/** One downloaded chapter of a series folder: a page folder (its files' total) or a CBZ file. */
data class ChapterEntry(val name: String, val size: Long)

/** A series folder. [size] counts its [chapters] and any other file directly in it. */
data class SeriesFolder(val name: String, val size: Long, val chapters: List<ChapterEntry>)

/** A source folder of the downloads root, named after a source's `toString()`. */
data class SourceFolder(val name: String, val series: List<SeriesFolder>)

/** An online or stub source and the folder name `DownloadProvider` gives it. */
data class KnownSource(val id: Long, val dirName: String)

/** A manga from the database and the folder name `DownloadProvider` gives it. */
data class KnownManga(val id: Long, val sourceId: Long, val dirName: String, val favorite: Boolean)

enum class SeriesStatus {
    IN_LIBRARY,
    NOT_IN_LIBRARY,

    /** No source or manga owns the folder, or several could: it is only listed, never deleted from here. */
    UNRECOGNISED,
}

/** A series folder with what owns it. [owner] is set for [SeriesStatus.IN_LIBRARY] and [SeriesStatus.NOT_IN_LIBRARY]. */
data class StoredSeries(
    val sourceFolder: String,
    val folder: SeriesFolder,
    val status: SeriesStatus,
    val owner: KnownManga?,
) {
    val size: Long get() = folder.size
}

/**
 * Matches folders to sources and manga as `DownloadCache` renewal does: a source folder belongs to every source whose
 * folder name equals it ignoring case (an installed source and a stub can share one), and a series folder to the manga
 * of those sources whose folder name equals it exactly.
 */
class DownloadMatcher(sources: List<KnownSource>, manga: List<KnownManga>) {

    private val sourceIdsByDir: Map<String, List<Long>> =
        sources.groupBy({ it.dirName.lowercase() }, { it.id }).mapValues { it.value.distinct() }

    private val mangaByKey: Map<MangaKey, List<KnownManga>> = manga.groupBy { MangaKey(it.sourceId, it.dirName) }

    fun sourceIds(sourceFolder: String): List<Long> = sourceIdsByDir[sourceFolder.lowercase()].orEmpty()

    fun matches(sourceIds: List<Long>, seriesFolder: String): List<KnownManga> =
        sourceIds.flatMap { mangaByKey[MangaKey(it, seriesFolder)].orEmpty() }.distinctBy { it.id }

    private data class MangaKey(val sourceId: Long, val dirName: String)
}

/**
 * One library entry owns the folder: in library. Otherwise a single non-library manga owns it: not in library.
 * Anything else (no source, no manga, two library entries or two non-library manga) is unrecognised. A library entry
 * outranks non-library manga sharing its folder, since the folder holds its downloads.
 */
fun classify(matches: List<KnownManga>): Pair<SeriesStatus, KnownManga?> {
    val favorites = matches.filter { it.favorite }
    return when {
        favorites.size == 1 -> SeriesStatus.IN_LIBRARY to favorites[0]
        favorites.isEmpty() && matches.size == 1 -> SeriesStatus.NOT_IN_LIBRARY to matches[0]
        else -> SeriesStatus.UNRECOGNISED to null
    }
}

/** Every series folder of [folders] with its status; see [classify]. */
fun classifyFolders(folders: List<SourceFolder>, matcher: DownloadMatcher): List<StoredSeries> =
    folders.flatMap { sourceFolder ->
        val sourceIds = matcher.sourceIds(sourceFolder.name)
        sourceFolder.series.map { series ->
            val (status, owner) = classify(matcher.matches(sourceIds, series.name))
            StoredSeries(sourceFolder.name, series, status, owner)
        }
    }

/**
 * The sources whose non-library manga could still own a folder: those of source folders holding a series folder no
 * library entry owns. Only their non-library manga need loading.
 */
fun sourcesToResolve(folders: List<SourceFolder>, matcher: DownloadMatcher): Set<Long> {
    val result = HashSet<Long>()
    for (sourceFolder in folders) {
        val sourceIds = matcher.sourceIds(sourceFolder.name)
        if (sourceIds.isEmpty()) continue
        if (sourceFolder.series.any { series -> matcher.matches(sourceIds, series.name).none { it.favorite } }) {
            result += sourceIds
        }
    }
    return result
}

/** One source folder's usage, its series largest first. */
data class SourceUsage(val name: String, val size: Long, val series: List<StoredSeries>)

data class StorageSummary(
    val total: Long,
    val seriesCount: Int,
    /** Largest first. */
    val sources: List<SourceUsage>,
    /** The largest series across sources, largest first. */
    val largest: List<StoredSeries>,
    val notInLibrary: List<StoredSeries>,
    val unrecognised: List<StoredSeries>,
)

fun summarize(series: List<StoredSeries>, largestCount: Int): StorageSummary {
    val bySize = series.sortedByDescending { it.size }
    val sources = bySize.groupBy { it.sourceFolder }
        .map { (name, items) -> SourceUsage(name, items.sumOf { it.size }, items) }
        .sortedByDescending { it.size }
    return StorageSummary(
        total = series.sumOf { it.size },
        seriesCount = series.size,
        sources = sources,
        largest = bySize.take(largestCount),
        notInLibrary = bySize.filter { it.status == SeriesStatus.NOT_IN_LIBRARY },
        unrecognised = bySize.filter { it.status == SeriesStatus.UNRECOGNISED },
    )
}

/**
 * The entry each chapter is downloaded as, by chapter id: the first of its possible folder names ([names], as
 * `DownloadProvider.getValidChapterDirNames` lists them) found among [entries], ignoring case as `DownloadProvider`
 * does when it matches a listing.
 */
fun <T> matchDownloads(
    entries: List<ChapterEntry>,
    chapters: List<T>,
    id: (T) -> Long,
    names: (T) -> List<String>,
): Map<Long, ChapterEntry> {
    if (entries.isEmpty()) return emptyMap()
    val byName = entries.associateByTo(TreeMap(String.CASE_INSENSITIVE_ORDER)) { it.name }
    val result = HashMap<Long, ChapterEntry>()
    for (chapter in chapters) {
        val entry = names(chapter).firstNotNullOfOrNull { byName[it] } ?: continue
        result[id(chapter)] = entry
    }
    return result
}

/** The download preferences and dedup settings a read-chapter cleanup of one series follows. */
data class ReadCleanup(
    /** `DownloadPreferences.removeBookmarkedChapters` is off. */
    val keepBookmarked: Boolean,
    /** The series is in a category of `DownloadPreferences.removeExcludeCategories`. */
    val inExcludedCategory: Boolean,
    /** A chapter also counts as read once another version of it (its dedup group) is read. */
    val readInAnotherVersion: Boolean,
    val mergeSplitChapters: Boolean,
)

/**
 * The [downloaded] chapters of one series to delete as read. Like `DownloadManager`'s delete filter, a series in an
 * excluded category keeps every read chapter and bookmarked chapters are kept unless the user deletes them too. With
 * [ReadCleanup.readInAnotherVersion], every copy (and repeated part upload) of a logical chapter counts as read once
 * one of its copies has all its parts read.
 */
fun readChaptersToDelete(
    chapters: List<DedupChapter>,
    read: Set<Long>,
    bookmarked: Set<Long>,
    downloaded: Set<Long>,
    cleanup: ReadCleanup,
): Set<Long> {
    if (cleanup.inExcludedCategory || downloaded.isEmpty()) return emptySet()
    val result = LinkedHashSet<Long>()
    for (chapter in chapters) {
        if (chapter.id in read && chapter.id in downloaded) result += chapter.id
    }
    if (cleanup.readInAnotherVersion) {
        for (logical in groupLogicalChapters(chapters, cleanup.mergeSplitChapters)) {
            if (logical.copies.none { copy -> copy.parts.all { it.id in read } }) continue
            for (copy in logical.copies) {
                for (part in copy.parts) if (part.id in downloaded) result += part.id
                for (part in copy.duplicates) if (part.id in downloaded) result += part.id
            }
        }
    }
    if (cleanup.keepBookmarked) result.removeAll(bookmarked)
    return result
}

/** Whether a series in [categoryIds] is excluded; one in no category is in the default category (id 0). */
fun inExcludedCategory(categoryIds: Collection<Long>, excluded: Set<Long>): Boolean {
    if (excluded.isEmpty()) return false
    if (categoryIds.isEmpty()) return DEFAULT_CATEGORY_ID in excluded
    return categoryIds.any { it in excluded }
}

private const val DEFAULT_CATEGORY_ID = 0L
