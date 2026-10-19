package haruka.migration

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A named, ordered list of migration target sources. */
@Serializable
data class SourcePreset(val name: String, val sourceIds: List<Long>)

/** What applying a preset selects, in preset order, and which of its sources are not available. */
data class PresetApplication(val selected: List<Long>, val missing: List<Long>)

/**
 * Source presets as stored in one string preference: a JSON list of `{name, sourceIds}` in the user's order.
 * Names match ignoring case and surrounding spaces, so "Main" and " main " are the same preset.
 */
object SourcePresets {

    private val json = Json { ignoreUnknownKeys = true }

    /** Unreadable text gives no presets; blank names are dropped and repeated names keep their first preset. */
    fun decode(text: String): List<SourcePreset> {
        if (text.isBlank()) return emptyList()
        val decoded = try {
            json.decodeFromString<List<SourcePreset>>(text)
        } catch (_: IllegalArgumentException) {
            // SerializationException is an IllegalArgumentException.
            return emptyList()
        }
        return decoded
            .map { SourcePreset(it.name.trim(), it.sourceIds.distinct()) }
            .filter { it.name.isNotEmpty() }
            .distinctBy { key(it.name) }
    }

    fun encode(presets: List<SourcePreset>): String = json.encodeToString(presets)

    fun find(presets: List<SourcePreset>, name: String): SourcePreset? = presets.firstOrNull { sameName(it.name, name) }

    /** Adds [name] at the end, or replaces the preset with that name in place. */
    fun save(presets: List<SourcePreset>, name: String, sourceIds: List<Long>): List<SourcePreset> {
        val preset = SourcePreset(name.trim(), sourceIds.distinct())
        if (preset.name.isEmpty()) return presets
        val index = presets.indexOfFirst { sameName(it.name, name) }
        return if (index == -1) presets + preset else presets.toMutableList().apply { set(index, preset) }
    }

    /** Renames in place; unchanged if [to] is blank, [from] is missing, or another preset already has [to]. */
    fun rename(presets: List<SourcePreset>, from: String, to: String): List<SourcePreset> {
        val newName = to.trim()
        val index = presets.indexOfFirst { sameName(it.name, from) }
        if (newName.isEmpty() || index == -1) return presets
        if (presets.withIndex().any { (i, preset) -> i != index && sameName(preset.name, newName) }) return presets
        return presets.toMutableList().apply { set(index, get(index).copy(name = newName)) }
    }

    fun delete(presets: List<SourcePreset>, name: String): List<SourcePreset> =
        presets.filterNot { sameName(it.name, name) }

    /** The preset's sources that are in [available], in preset order, and the ones that are not. */
    fun apply(preset: SourcePreset, available: Collection<Long>): PresetApplication {
        val (selected, missing) = preset.sourceIds.partition { it in available }
        return PresetApplication(selected, missing)
    }

    fun sameName(a: String, b: String): Boolean = key(a) == key(b)

    private fun key(name: String) = name.trim().lowercase()
}
