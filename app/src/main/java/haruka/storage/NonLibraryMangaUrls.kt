package haruka.storage

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import dev.zacsweers.metro.Inject

/**
 * The urls of every manga not in the library, by source id. Mihon has no query for this (it dropped
 * `getAllMangaSourceAndUrl` in 3ed83a8) and fork code adds no `.sq` queries, so this reads the manga table through the
 * app's driver. Column names follow `data/src/main/sqldelight/tachiyomi/data/manga.sq`.
 */
@Inject
class NonLibraryMangaUrls(
    private val driver: SqlDriver,
) {

    suspend fun bySource(): Map<Long, List<String>> = driver.executeQuery(
        identifier = null,
        sql = "SELECT source_id, remote_url FROM manga WHERE user_favorite_at IS NULL",
        mapper = { cursor ->
            val urls = HashMap<Long, MutableList<String>>()
            while (cursor.next().value) {
                urls.getOrPut(cursor.getLong(0)!!) { ArrayList() }.add(cursor.getString(1)!!)
            }
            QueryResult.Value(urls)
        },
        parameters = 0,
    ).await()
}
