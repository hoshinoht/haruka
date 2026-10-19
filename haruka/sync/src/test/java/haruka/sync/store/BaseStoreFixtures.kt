package haruka.sync.store

import haruka.sync.model.BUCKET_COUNT
import haruka.sync.model.CategoriesPayload
import haruka.sync.model.CategoryRecord
import haruka.sync.model.ChapterRecord
import haruka.sync.model.DeviceRecord
import haruka.sync.model.EntryKey
import haruka.sync.model.EntryRecord
import haruka.sync.model.FORMAT_VERSION
import haruka.sync.model.ManifestBody
import haruka.sync.model.ManifestHeader
import haruka.sync.model.PrefRecord
import haruka.sync.model.PrefsPayload
import java.nio.file.Files
import java.nio.file.Path

/** Everything a snapshot holds, read back in full so tests compare whole generations. */
data class SnapshotContents(
    val header: ManifestHeader,
    val body: ManifestBody,
    val buckets: List<BaseBucket>,
    val categories: CategoriesPayload,
    val prefs: PrefsPayload,
)

fun BaseSnapshot.contents() = SnapshotContents(
    header = header,
    body = body,
    buckets = (0..<BUCKET_COUNT).map(::readBucket),
    categories = readCategories(),
    prefs = readPrefs(),
)

fun header(seq: Long) = ManifestHeader(formatVersion = FORMAT_VERSION, remoteEpoch = "epoch-1", commitSeq = seq)

fun body(seq: Long) = ManifestBody(
    commitId = "commit-$seq",
    writerDeviceId = "device-a",
    writtenAtMillis = seq * 1000,
    bucketCount = BUCKET_COUNT,
    buckets = emptyList(),
    devices = listOf(DeviceRecord("device-a", "Pixel", "commit-$seq", seq, seq * 1000)),
)

/** A canonical bucket whose content depends on [tag], so two generations never compare equal. */
fun bucket(index: Int, tag: String) = BaseBucket(
    index = index,
    entries = listOf(
        BaseEntry(
            record = EntryRecord(
                key = EntryKey(sourceId = index.toLong(), url = "/m/$index"),
                favorite = true,
                favoriteAtMillis = 1000,
                title = "Entry $index",
                notes = tag,
                categoryUuids = listOf("cat-1"),
                chapters = listOf(
                    ChapterRecord(
                        url = "/c/1",
                        name = "Ch. 1",
                        read = true,
                        historyReadAtMillis = 5,
                        historyDurationMs = 7,
                    ),
                    ChapterRecord(url = "/c/2", name = "Ch. 2", lastPageRead = 3),
                    ChapterRecord(url = "/c/3", name = "Ch. 3"),
                ),
            ),
            absentChapterUrls = listOf("/c/3"),
        ),
    ),
)

fun categories(tag: String) = CategoriesPayload(
    listOf(CategoryRecord(uuid = "cat-1", name = "Reading $tag", order = 1)),
)

fun prefs(tag: String) = PrefsPayload(listOf(PrefRecord(key = "haruka_pref", value = tag)))

/** Stages a full snapshot for [seq] whose content is tagged [tag]; the caller activates it. */
fun BaseStore.stageFull(seq: Long, tag: String): StagedSnapshot {
    val staged = stage(header(seq), body(seq))
    for (index in 0..<BUCKET_COUNT) staged.writeBucket(bucket(index, tag))
    staged.writeCategories(categories(tag))
    staged.writePrefs(prefs(tag))
    return staged
}

fun expectedContents(seq: Long, tag: String) = SnapshotContents(
    header = header(seq),
    body = body(seq),
    buckets = (0..<BUCKET_COUNT).map { bucket(it, tag) },
    categories = categories(tag),
    prefs = prefs(tag),
)

fun Path.childNames(): Set<String> = Files.newDirectoryStream(this).use { entries ->
    entries.map { it.fileName.toString() }.toSet()
}

fun Path.copyTreeTo(target: Path) {
    Files.walk(this).use { paths ->
        paths.forEach { Files.copy(it, target.resolve(relativize(it).toString())) }
    }
}
