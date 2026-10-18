package haruka.chapter.dedup

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class OfficialStatus {
    NONE,

    /** Marked official with doubt, e.g. "Official?". */
    UNCERTAIN,
    OFFICIAL,
}

/**
 * Known official publishers, keyed by [ScanlatorNames.normalize]d alias, plus the sources that are publishers
 * themselves (every chapter they serve is official) and the aggregator sources that mirror many groups under
 * pseudonyms (where only a chapter's name can tell an official translation apart).
 */
class OfficialCatalog(
    private val publishersByAlias: Map<String, String>,
    private val officialSources: Set<String>,
    private val aggregatorSources: Set<String> = emptySet(),
) {

    /** Canonical publisher of the first official group in [scanlator], or null. Ignores "?" doubt marks. */
    fun publisherOf(scanlator: String?): String? {
        if (scanlator.isNullOrBlank()) return null
        for (part in ScanlatorNames.split(scanlator)) {
            publishersByAlias[ScanlatorNames.normalize(part)]?.let { return it }
        }
        return null
    }

    /**
     * Best status among the groups in [scanlator]: any plainly official group makes it [OfficialStatus.OFFICIAL];
     * an official group carrying a '?' makes it [OfficialStatus.UNCERTAIN].
     */
    fun statusOf(scanlator: String?): OfficialStatus {
        if (scanlator.isNullOrBlank()) return OfficialStatus.NONE
        var status = OfficialStatus.NONE
        for (part in ScanlatorNames.split(scanlator)) {
            if (ScanlatorNames.normalize(part) !in publishersByAlias) continue
            if ('?' !in part) return OfficialStatus.OFFICIAL
            status = OfficialStatus.UNCERTAIN
        }
        return status
    }

    fun isOfficialSource(sourceName: String): Boolean = ScanlatorNames.normalize(sourceName) in officialSources

    fun isAggregatorSource(sourceName: String): Boolean = ScanlatorNames.normalize(sourceName) in aggregatorSources

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): OfficialCatalog {
            val data = json.decodeFromString(CatalogJson.serializer(), text)
            val publishersByAlias = HashMap<String, String>()
            for (publisher in data.publishers) {
                for (alias in publisher.aliases + publisher.name) {
                    val key = ScanlatorNames.normalize(alias)
                    require(key.isNotEmpty()) { "Alias '$alias' normalizes to nothing" }
                    val previous = publishersByAlias.put(key, publisher.name)
                    require(previous == null || previous == publisher.name) {
                        "Alias '$alias' maps to both '$previous' and '${publisher.name}'"
                    }
                }
            }
            val sources = data.sources.mapTo(HashSet()) { ScanlatorNames.normalize(it) }
            val aggregators = data.aggregatorSources.mapTo(HashSet()) { ScanlatorNames.normalize(it) }
            return OfficialCatalog(publishersByAlias, sources, aggregators)
        }
    }
}

/** Reads the bundled catalog asset once, on first use. */
@Inject
@SingleIn(AppScope::class)
class OfficialCatalogLoader(private val context: Context) {

    val catalog: OfficialCatalog by lazy {
        context.assets.open(ASSET_PATH).bufferedReader().use { OfficialCatalog.parse(it.readText()) }
    }

    companion object {
        const val ASSET_PATH = "haruka/official-scanlators.json"
    }
}

@Serializable
private class CatalogJson(
    val version: Int,
    val publishers: List<PublisherJson>,
    val sources: List<String>,
    val aggregatorSources: List<String> = emptyList(),
)

@Serializable
private class PublisherJson(
    val name: String,
    val aliases: List<String>,
)
