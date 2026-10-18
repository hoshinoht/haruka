package haruka.updateerrors

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File

/** One entry that failed in the last library update. */
@Serializable
data class UpdateError(
    val mangaId: Long,
    val title: String,
    val sourceId: Long,
    val message: String? = null,
)

/**
 * Groups failures by error message: biggest group first (ties keep first-appearance order), entries by title.
 */
fun groupByMessage(errors: List<UpdateError>): List<Pair<String?, List<UpdateError>>> =
    errors.groupBy { it.message }
        .map { (message, entries) ->
            message to
                entries.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
        }
        .sortedByDescending { it.second.size }

/**
 * Failures of the last library update, kept in a fork-owned JSON file so no upstream table is needed.
 *
 * The library update replaces the content when a run starts (empty) and again when it ends (the failures), so
 * the file always describes the latest run only.
 */
@SingleIn(AppScope::class)
class UpdateErrorStore internal constructor(
    private val file: File,
    private val json: Json,
) {

    @Inject
    constructor(context: Context, json: Json) : this(File(context.filesDir, "haruka/update_errors.json"), json)

    private val mutex = Mutex()

    /** `null` until the file has been read once. */
    private val state = MutableStateFlow<List<UpdateError>?>(null)

    /** Failures of the last run; the file is read on first collection. */
    val errors: Flow<List<UpdateError>> = state
        .onStart { load() }
        .filterNotNull()

    suspend fun replace(errors: List<UpdateError>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                file.parentFile?.mkdirs()
                file.writeText(json.encodeToString(errors))
            } catch (e: Exception) {
                // Losing the list must never fail the library update itself.
                logcat(LogPriority.ERROR, e)
            }
            state.value = errors
        }
    }

    private suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (state.value == null) state.value = read()
        }
    }

    private fun read(): List<UpdateError> {
        if (!file.exists()) return emptyList()
        return try {
            json.decodeFromString(file.readText())
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            emptyList()
        }
    }
}
