package haruka.chapter.dedup

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

/** Per-manga choices; null fields follow the global setting. */
@Serializable
data class MangaDedupOverride(
    val groupChapters: Boolean? = null,
    val mergeSplitChapters: Boolean? = null,
    /** Replaces the global ranking for this manga when non-null. */
    val rankedScanlators: List<String>? = null,
)

/** Everything a chapter surface needs to dedupe one manga. */
data class EffectiveDedupConfig(
    val groupChapters: Boolean,
    val markDuplicatesRead: Boolean,
    val skipDownloadedDuplicates: Boolean,
    val options: DedupOptions,
)

/**
 * Dedup preferences, global plus per-manga overrides. All on-device; keys are prefixed `haruka_dedup_`.
 *
 * Shared contract for the chapter-list, reader, download and settings integrations.
 */
@Inject
@SingleIn(AppScope::class)
class DedupSettings(preferenceStore: PreferenceStore, private val catalogLoader: OfficialCatalogLoader) {

    private val json = Json { ignoreUnknownKeys = true }

    /** Collapse a chapter's copies (and merged split parts) into one row in the chapter list. */
    val groupChapters: Preference<Boolean> = preferenceStore.getBoolean("haruka_dedup_group_chapters", true)

    val mergeSplitChapters: Preference<Boolean> = preferenceStore.getBoolean("haruka_dedup_merge_split", true)

    /** Finishing a chapter's chosen copy marks its other copies read. */
    val markDuplicatesRead: Preference<Boolean> =
        preferenceStore.getBoolean("haruka_dedup_mark_duplicates_read", true)

    /** Auto-download skips chapter numbers that already have a downloaded copy. */
    val skipDownloadedDuplicates: Preference<Boolean> =
        preferenceStore.getBoolean("haruka_dedup_skip_downloaded_duplicates", true)

    val rules: Preference<List<DedupRule>> = preferenceStore.getObjectFromString(
        key = "haruka_dedup_rules",
        defaultValue = DedupRule.DEFAULT,
        serializer = { rules -> rules.joinToString(",") { it.name } },
        deserializer = { text ->
            val parsed = text.split(',').mapNotNull { name -> DedupRule.entries.firstOrNull { it.name == name } }
            // Rules added in later versions are appended so an older saved order never drops them.
            parsed + DedupRule.DEFAULT.filterNot { it in parsed }
        },
    )

    /** Global scanlator ranking, best first. */
    val rankedScanlators: Preference<List<String>> = preferenceStore.getObjectFromString(
        key = "haruka_dedup_ranking",
        defaultValue = emptyList(),
        serializer = { json.encodeToString(it) },
        deserializer = { runCatching { json.decodeFromString<List<String>>(it) }.getOrDefault(emptyList()) },
    )

    private val overrides: Preference<Map<Long, MangaDedupOverride>> = preferenceStore.getObjectFromString(
        key = "haruka_dedup_manga_overrides",
        defaultValue = emptyMap(),
        serializer = { json.encodeToString(it) },
        deserializer = {
            runCatching { json.decodeFromString<Map<Long, MangaDedupOverride>>(it) }.getOrDefault(emptyMap())
        },
    )

    fun override(mangaId: Long): MangaDedupOverride? = overrides.get()[mangaId]

    /** Stores [override]; an all-null override is removed so the map only holds real choices. */
    fun setOverride(mangaId: Long, override: MangaDedupOverride?) {
        val current = overrides.get()
        val clean = override?.takeUnless { it == MangaDedupOverride() }
        overrides.set(if (clean == null) current - mangaId else current + (mangaId to clean))
    }

    fun config(mangaId: Long, sourceName: String): Flow<EffectiveDedupConfig> = combine(
        combine(groupChapters.changes(), mergeSplitChapters.changes(), ::Pair),
        combine(markDuplicatesRead.changes(), skipDownloadedDuplicates.changes(), ::Pair),
        rules.changes(),
        rankedScanlators.changes(),
        overrides.changes(),
    ) { (group, merge), (markRead, skipDownloaded), rules, ranking, overrides ->
        val override = overrides[mangaId]
        EffectiveDedupConfig(
            groupChapters = override?.groupChapters ?: group,
            markDuplicatesRead = markRead,
            skipDownloadedDuplicates = skipDownloaded,
            options = DedupOptions(
                rules = rules,
                rankedScanlators = override?.rankedScanlators ?: ranking,
                mergeSplitChapters = override?.mergeSplitChapters ?: merge,
                sourceIsOfficial = catalogLoader.catalog.isOfficialSource(sourceName),
            ),
        )
    }
}
