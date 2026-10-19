package haruka.sync.codec

import haruka.sync.model.BUCKET_COUNT
import haruka.sync.model.Bucket
import haruka.sync.model.BucketRef
import haruka.sync.model.CategoriesPayload
import haruka.sync.model.CategoryRecord
import haruka.sync.model.ChapterRecord
import haruka.sync.model.DedupOverrideRecord
import haruka.sync.model.DeviceRecord
import haruka.sync.model.EncryptionParams
import haruka.sync.model.EntryKey
import haruka.sync.model.EntryRecord
import haruka.sync.model.FORMAT_VERSION
import haruka.sync.model.ManifestBody
import haruka.sync.model.ManifestHeader
import haruka.sync.model.PayloadRef
import haruka.sync.model.PrefRecord
import haruka.sync.model.PrefsPayload
import haruka.sync.model.ScanlatorRanking
import haruka.sync.model.TrackRecord
import haruka.sync.model.canonical
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import kotlin.random.Random

/*
 * Generators for canonical sync records. Values lean towards defaults (so sparse encoding is exercised) and include
 * non-ASCII text, negative numbers and boundary values. Keyed collections draw from small key spaces so duplicate
 * keys occur and are dropped before canonicalization, as the database would never produce them.
 */
object SyncArbs {

    val bucket: Arb<Bucket> = arbitrary { rs -> rs.random.bucket() }
    val categories: Arb<CategoriesPayload> = arbitrary { rs -> rs.random.categories() }
    val prefs: Arb<PrefsPayload> = arbitrary { rs -> rs.random.prefs() }
    val manifestHeader: Arb<ManifestHeader> = arbitrary { rs -> rs.random.manifestHeader() }
    val manifestBody: Arb<ManifestBody> = arbitrary { rs -> rs.random.manifestBody() }

    // Includes a 2-byte, a 3-byte and a surrogate-pair (4-byte UTF-8) character.
    private val symbols =
        listOf("a", "b", "c", "X", "Z", "0", "9", " ", "/", "-", ".", "\u00e9", "\u3042", "\uD83D\uDE00")

    private fun Random.text(maxLength: Int = 12): String = buildString {
        repeat(nextInt(0, maxLength + 1)) { append(symbols.random(this@text)) }
    }

    private fun Random.chance(percent: Int) = nextInt(100) < percent

    private fun Random.long(): Long = when (nextInt(6)) {
        0 -> 0L
        1 -> Long.MIN_VALUE
        2 -> Long.MAX_VALUE
        3 -> -1L
        else -> nextLong()
    }

    private fun Random.int(): Int = when (nextInt(5)) {
        0 -> 0
        1 -> Int.MIN_VALUE
        2 -> Int.MAX_VALUE
        else -> nextInt()
    }

    private fun Random.double(): Double = when (nextInt(5)) {
        0 -> 0.0
        1 -> -1.0
        2 -> Double.MAX_VALUE
        else -> nextDouble(-10_000.0, 10_000.0)
    }

    private fun Random.bytes(): ByteArray = nextBytes(nextInt(0, 33))

    private fun <T> Random.orNull(percent: Int = 50, value: () -> T): T? = if (chance(percent)) value() else null

    private fun <T> Random.listUpTo(max: Int, element: () -> T): List<T> = List(nextInt(0, max + 1)) { element() }

    private fun Random.textSet(max: Int): List<String> = listUpTo(max) { text(6) }.distinct()

    private fun Random.bucket(): Bucket {
        val entries = listUpTo(6) { entry() }.distinctBy { it.key }
        return Bucket(index = nextInt(BUCKET_COUNT), entries = entries).canonical()
    }

    private fun Random.entry(): EntryRecord {
        val favorite = nextBoolean()
        return EntryRecord(
            key = EntryKey(sourceId = if (chance(30)) long() else nextLong(0, 4), url = text(8)),
            favorite = favorite,
            favoriteAtMillis = orNull { long() },
            removedAtMillis = if (!favorite) orNull(30) { long() } else null,
            title = text(),
            author = orNull { text() },
            artist = orNull { text() },
            description = orNull { text(40) },
            genre = listUpTo(4) { text(6) },
            status = if (chance(50)) nextLong(0, 7) else 0,
            thumbnailUrl = orNull { text(30) },
            initialized = nextBoolean(),
            updateStrategy = if (chance(30)) 1 else 0,
            memoJson = if (chance(20)) "{\"k\":\"${text(6)}\"}" else "",
            viewerFlags = if (chance(50)) long() else 0,
            chapterFlags = if (chance(50)) long() else 0,
            notes = if (chance(30)) text(40) else "",
            categoryUuids = textSet(3),
            excludedScanlators = textSet(3),
            dedupOverride = orNull(30) { dedupOverride() },
            chapters = listUpTo(12) { chapter() }.distinctBy { it.url },
            tracks = listUpTo(3) { track() }.distinctBy { it.trackerId },
        )
    }

    private fun Random.dedupOverride() = DedupOverrideRecord(
        groupChapters = orNull { nextBoolean() },
        mergeSplitChapters = orNull { nextBoolean() },
        rankedScanlators = orNull { ScanlatorRanking(listUpTo(3) { text(6) }) },
    )

    private fun Random.chapter(): ChapterRecord {
        val readAt = orNull(30) { long() }
        return ChapterRecord(
            url = text(8),
            name = text(),
            chapterNumber = double(),
            scanlator = orNull { text(6) },
            sourceOrder = if (chance(70)) nextLong(0, 1_000) else long(),
            dateUploadMillis = orNull { long() },
            memoJson = if (chance(10)) "{}" else "",
            read = chance(40),
            bookmark = chance(10),
            lastPageRead = if (chance(30)) long() else 0,
            historyReadAtMillis = readAt,
            historyDurationMs = if (readAt != null && chance(70)) long() else 0,
        )
    }

    private fun Random.track() = TrackRecord(
        trackerId = nextLong(1, 6),
        remoteId = long(),
        libraryId = orNull { long() },
        title = text(),
        lastChapterRead = double(),
        totalChapters = long(),
        status = nextLong(0, 7),
        score = double(),
        remoteUrl = text(20),
        startDateMillis = orNull { long() },
        finishDateMillis = orNull { long() },
        private = nextBoolean(),
    )

    private fun Random.categories(): CategoriesPayload = CategoriesPayload(
        listUpTo(8) {
            CategoryRecord(
                uuid = text(6),
                name = text(),
                order = long(),
                flags = long(),
                deletedAtMillis = orNull(20) { long() },
            )
        }.distinctBy { it.uuid },
    ).canonical()

    private fun Random.prefs(): PrefsPayload =
        PrefsPayload(listUpTo(12) { PrefRecord(key = text(6), value = text()) }.distinctBy { it.key }).canonical()

    private fun Random.manifestHeader() = ManifestHeader(
        formatVersion = if (chance(80)) FORMAT_VERSION else int(),
        remoteEpoch = text(36),
        commitSeq = long(),
        encryption = orNull {
            EncryptionParams(
                keyId = text(8),
                kdfAlgorithm = "PBKDF2WithHmacSHA256",
                kdfIterations = int(),
                kdfSalt = bytes(),
                keyCheck = bytes(),
            )
        },
    )

    private fun Random.payloadRef() = PayloadRef(
        fileId = text(20),
        name = text(30),
        storedSha = text(64),
        plaintextSha = text(64),
        entryCount = int(),
    )

    private fun Random.manifestBody() = ManifestBody(
        commitId = text(36),
        writerDeviceId = text(36),
        writtenAtMillis = long(),
        bucketCount = BUCKET_COUNT,
        buckets = listUpTo(10) {
            BucketRef(index = nextInt(BUCKET_COUNT), payload = payloadRef())
        }.distinctBy { it.index },
        categoriesRef = orNull { payloadRef() },
        prefsRef = orNull { payloadRef() },
        devices = listUpTo(4) {
            DeviceRecord(
                id = text(8),
                model = text(),
                lastCommitId = orNull { text(36) },
                lastCommitSeq = long(),
                lastSyncAtMillis = long(),
            )
        }.distinctBy { it.id },
    ).canonical()
}
