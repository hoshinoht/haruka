package haruka.sync.model

import java.security.MessageDigest

/** Entries are spread over this many bucket files (about 7–8 entries per bucket at 950 entries). */
const val BUCKET_COUNT = 128

/** The bucket of [key]: the first 4 bytes of SHA-256("$sourceId\u0000$url"), unsigned big-endian, mod [BUCKET_COUNT]. */
fun bucketOf(key: EntryKey): Int {
    val hash = MessageDigest.getInstance("SHA-256").digest("${key.sourceId}\u0000${key.url}".encodeToByteArray())
    val prefix = (hash[0].toLong() and 0xFF shl 24) or
        (hash[1].toLong() and 0xFF shl 16) or
        (hash[2].toLong() and 0xFF shl 8) or
        (hash[3].toLong() and 0xFF)
    return (prefix % BUCKET_COUNT).toInt()
}
