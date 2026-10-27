package haruka.storage

import android.content.Context
import android.os.SystemClock
import android.text.format.Formatter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import eu.kanade.presentation.more.settings.Preference
import haruka.di.harukaGraph
import haruka.i18n.HR
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import logcat.LogPriority
import mihon.core.common.cache.TtlDiskCache
import mihon.data.cache.ChapterCache
import tachiyomi.core.common.util.system.logcat
import tachiyomi.presentation.core.i18n.stringResource
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps the online page images in the chapter cache under the size set in Data and storage.
 *
 * Mihon keeps those images for a day with no size limit, so binge reading with load-ahead can fill the cache with
 * whole chapters. With a limit set, the images written longest ago are removed once the cache grows past it. A limit
 * of 0, the default, never trims, which is Mihon's behaviour.
 *
 * Trimming runs off the reader's path, at most once per [TRIM_EVERY_WRITES] writes or [TRIM_INTERVAL_MS], whichever
 * comes first, and never twice at once, so the cache can overshoot the limit by those few images in between.
 */
object ChapterCacheCap {

    /** The limits offered, in MB. Sizes are SI, as Mihon's "Used:" line shows them. */
    val LIMITS_MB = listOf(0, 250, 500, 1000, 2000)

    const val TRIM_EVERY_WRITES = 20

    const val TRIM_INTERVAL_MS = 30_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val trimming = AtomicBoolean(false)

    // Guarded by this
    private var writesSinceTrim = 0
    private var lastTrimAt = Long.MIN_VALUE

    /** Whether a trim is due, [writes] writes and [elapsedMs] since the last one. */
    fun shouldTrim(writes: Int, elapsedMs: Long): Boolean =
        writes >= TRIM_EVERY_WRITES || elapsedMs >= TRIM_INTERVAL_MS

    fun limitBytes(limitMb: Int): Long = limitMb * 1_000_000L

    /** Called after each image is written to [images], the chapter cache's image store. */
    fun afterWrite(context: Context, images: TtlDiskCache) {
        val limitMb = context.harukaGraph.harukaPreferences.chapterCacheLimitMb.get()
        if (limitMb <= 0) return
        val now = SystemClock.elapsedRealtime()
        val due = synchronized(this) {
            writesSinceTrim++
            val elapsed = if (lastTrimAt == Long.MIN_VALUE) Long.MAX_VALUE else now - lastTrimAt
            if (!shouldTrim(writesSinceTrim, elapsed)) return@synchronized false
            writesSinceTrim = 0
            lastTrimAt = now
            true
        }
        if (!due || !trimming.compareAndSet(false, true)) return
        scope.launch {
            try {
                trim(limitMb) { images.trimToSize(it) }
            } finally {
                trimming.set(false)
            }
        }
    }

    /** Trims [chapterCache] to [limitMb] now, as when the limit changes. Returns how many images it removed. */
    suspend fun trimNow(chapterCache: ChapterCache, limitMb: Int): Int {
        if (limitMb <= 0) return 0
        return trim(limitMb) { chapterCache.trimImages(it) }
    }

    private suspend fun trim(limitMb: Int, trimToSize: suspend (Long) -> Int): Int = try {
        trimToSize(limitBytes(limitMb)).also { removed ->
            if (removed > 0) logcat(LogPriority.DEBUG) { "Chapter cache over $limitMb MB, removed $removed images" }
        }
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        logcat(LogPriority.WARN, e) { "Failed to trim the chapter cache" }
        0
    }
}

/** Settings > Data and storage item choosing [ChapterCacheCap]'s limit. [onTrimmed] runs after a trim it causes. */
@Composable
fun chapterCacheLimitPreference(
    chapterCache: ChapterCache,
    onTrimmed: () -> Unit,
): Preference.PreferenceItem.ListPreference<Int> {
    val context = LocalContext.current
    val preference = remember { context.harukaGraph.harukaPreferences.chapterCacheLimitMb }
    val noLimit = stringResource(HR.strings.chapter_cache_limit_none)
    val entries = remember(noLimit) {
        ChapterCacheCap.LIMITS_MB.associateWith { mb ->
            if (mb == 0) noLimit else Formatter.formatShortFileSize(context, ChapterCacheCap.limitBytes(mb))
        }
    }
    return Preference.PreferenceItem.ListPreference(
        preference = preference,
        entries = entries,
        title = stringResource(HR.strings.chapter_cache_limit),
        subtitleProvider = { value, items ->
            "${items[value]}\n${stringResource(HR.strings.chapter_cache_limit_summary)}"
        },
        onValueChanged = { limitMb ->
            if (ChapterCacheCap.trimNow(chapterCache, limitMb) > 0) onTrimmed()
            true
        },
    )
}
