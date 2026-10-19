package haruka.sync.engine

import haruka.sync.model.EntryKey
import haruka.sync.model.EntryRecord
import haruka.sync.model.PrefsPayload

/** A live local category as the database holds it; the engine maps it to a uuid. */
data class LocalCategoryRow(val id: Long, val name: String, val order: Long, val flags: Long)

/**
 * Read side of this device's library, implemented by the app glue over Mihon's repositories. The engine reads
 * categories first (it needs the uuid map to translate memberships), then the prefs, then the entries one bucket at
 * a time, so the whole library never has to be in memory at once.
 */
interface LocalSource {

    suspend fun readCategories(): List<LocalCategoryRow>

    /** The whitelisted fork prefs (`SYNCED_PREF_KEYS`) that are set; others are ignored. */
    suspend fun readPrefs(): PrefsPayload

    /**
     * The local rows of bucket [index]: every favorite with `bucketOf(key) == index`, plus the rows of [knownKeys]
     * (entries the base or the remote holds in this bucket) that exist in the database, favorite or not.
     *
     * Each record lists the chapter rows observed locally with their state (history duration 0 when readAt is
     * null), and its category memberships translated through [categoryUuids] (local category id to uuid).
     * Called once per bucket, in ascending order.
     */
    suspend fun readBucket(index: Int, knownKeys: Set<EntryKey>, categoryUuids: Map<Long, String>): List<EntryRecord>
}
