package haruka.backup

import android.content.Context
import android.net.Uri
import okio.Buffer
import okio.BufferedSource
import okio.buffer
import okio.gzip
import okio.source
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.IOException

/**
 * The tracker ids (`BackupTracking.syncId`) of every entry in the backup at [uri], read off the wire without
 * decoding the entries. Checking a backup only needs these from the entries, and decoding every chapter and history
 * row of a large library to get them takes seconds.
 *
 * Null when the backup isn't in the shape the scan expects or can't be read: the caller then decodes it the usual
 * way, which keeps its result, and its error, for odd or broken files.
 */
fun scanTrackerIds(uri: Uri): Set<Int>? = try {
    Injekt.get<Context>().contentResolver.openInputStream(uri)?.source()?.buffer()?.use { source ->
        val peeked = source.peek()
        peeked.require(2)
        if (peeked.readShort().toInt() == GZIP_MAGIC) {
            source.gzip().buffer().use(::scanTrackerIds)
        } else {
            scanTrackerIds(source)
        }
    }
} catch (_: Exception) {
    null
}

/**
 * [scanTrackerIds] over a decompressed backup, or null when it isn't well formed. Fields are read the way
 * kotlinx.serialization decodes them: every entry and tracking element counts, the last `syncId` of an element wins,
 * and a known field with the wrong wire type, or an element without a `syncId`, is malformed.
 */
internal fun scanTrackerIds(source: BufferedSource): Set<Int>? = try {
    val trackerIds = mutableSetOf<Int>()
    source.forEachField { field, wireType ->
        if (field == BACKUP_ENTRY) {
            readMessage(wireType).forEachField { entryField, entryWireType ->
                if (entryField == ENTRY_TRACKING) {
                    trackerIds += readMessage(entryWireType).readSyncId()
                } else {
                    skipValue(entryWireType)
                }
            }
        } else {
            skipValue(wireType)
        }
    }
    trackerIds
} catch (_: IOException) {
    null
}

private fun Buffer.readSyncId(): Int {
    var syncId: Int? = null
    forEachField { field, wireType ->
        if (field == TRACKING_SYNC_ID) {
            if (wireType != WIRE_VARINT) throw IOException("syncId is not a varint")
            syncId = readVarint().toInt()
        } else {
            skipValue(wireType)
        }
    }
    return syncId ?: throw IOException("Tracking without syncId")
}

private inline fun <S : BufferedSource> S.forEachField(block: S.(field: Long, wireType: Int) -> Unit) {
    while (!exhausted()) {
        val tag = readVarint()
        val field = tag ushr 3
        if (field == 0L) throw IOException("Invalid field number 0")
        block(field, (tag and 0x7).toInt())
    }
}

/** The length-delimited message at the current field, as a buffer of its own. */
private fun BufferedSource.readMessage(wireType: Int): Buffer {
    if (wireType != WIRE_LENGTH_DELIMITED) throw IOException("Message field is not length-delimited")
    return Buffer().also { readFully(it, readLength()) }
}

private fun BufferedSource.skipValue(wireType: Int) {
    when (wireType) {
        WIRE_VARINT -> readVarint()
        WIRE_FIXED64 -> skip(8)
        WIRE_LENGTH_DELIMITED -> skip(readLength())
        WIRE_FIXED32 -> skip(4)
        else -> throw IOException("Unsupported wire type $wireType")
    }
}

private fun BufferedSource.readLength(): Long {
    val length = readVarint()
    if (length !in 0..Int.MAX_VALUE) throw IOException("Invalid length $length")
    return length
}

private fun BufferedSource.readVarint(): Long {
    var result = 0L
    for (shift in 0 until Long.SIZE_BITS step 7) {
        val byte = readByte().toInt()
        result = result or ((byte and 0x7F).toLong() shl shift)
        if ((byte and 0x80) == 0) return result
    }
    throw IOException("Malformed varint")
}

private const val GZIP_MAGIC = 0x1f8b

private const val WIRE_VARINT = 0
private const val WIRE_FIXED64 = 1
private const val WIRE_LENGTH_DELIMITED = 2
private const val WIRE_FIXED32 = 5

// Backup.backupManga, BackupManga.tracking, BackupTracking.syncId
private const val BACKUP_ENTRY = 1L
private const val ENTRY_TRACKING = 18L
private const val TRACKING_SYNC_ID = 1L
