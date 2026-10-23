package haruka.library

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.tachiyomi.util.system.toast
import haruka.di.harukaGraph
import haruka.i18n.HR
import haruka.preference.HarukaPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.core.metro.AppCoroutineScope
import mihon.domain.source.interactor.UpdateMangaFromRemote
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * Loads details and chapters of a series added from a listing (browse, global search, related titles). A listing
 * only carries a title and cover, so without this the series sits in the library with no chapters until the next
 * library update. Adding from the series screen needs none of this: that screen already loaded everything.
 *
 * One series at a time: extensions rate-limit per source, and a burst of adds shouldn't fire a request each.
 */
@Inject
@SingleIn(AppScope::class)
class FetchOnAdd(
    @AppCoroutineScope appScope: CoroutineScope,
    private val context: Context,
    private val preferences: HarukaPreferences,
    private val updateMangaFromRemote: UpdateMangaFromRemote,
    private val getManga: GetManga,
    private val updateManga: UpdateManga,
) {

    private val queue = Channel<Manga>(Channel.UNLIMITED)

    init {
        appScope.launch {
            for (manga in queue) fetch(manga)
        }
    }

    /** Call after [manga] was added to the library; a no-op when the setting is off. */
    fun enqueue(manga: Manga) {
        if (preferences.fetchOnAdd.get()) queue.trySend(manga)
    }

    private suspend fun fetch(manga: Manga) {
        val update = updateMangaFromRemote(manga, fetchDetails = true, fetchChapters = true).getOrElse {
            withContext(Dispatchers.Main) {
                context.toast(context.stringResource(HR.strings.fetch_on_add_failed, manga.title))
            }
            return
        }
        val addedAt = addedAfterFirstFetch(Clock.System.now(), update.newChapters.map { it.dateFetch }) ?: return
        // Removed again while loading: leave it out of the library.
        if (getManga.await(manga.id)?.favorite != true) return
        updateManga.await(MangaUpdate(manga.id) { favoriteAt = addedAt })
    }
}

/**
 * When to say [now]-added series was added, so its first chapter list ([fetchedAt]) stays out of Updates, which
 * only lists chapters fetched after the series was added. Chapter sync stamps a list ahead of the clock (a
 * millisecond per chapter, to keep its order), so [now] alone could fall before them. Null when nothing was fetched.
 */
internal fun addedAfterFirstFetch(now: Instant, fetchedAt: List<Instant>): Instant? {
    val latest = fetchedAt.maxOrNull() ?: return null
    return maxOf(now, latest + 1.milliseconds)
}

/** Hands a just-added [manga] to [FetchOnAdd]; for upstream add paths, which have no fork dependencies. */
fun fetchOnAdd(manga: Manga) {
    Injekt.get<Context>().harukaGraph.fetchOnAdd.enqueue(manga)
}

@Composable
fun fetchOnAddPreference(): Preference.PreferenceItem.SwitchPreference {
    val context = LocalContext.current
    return Preference.PreferenceItem.SwitchPreference(
        preference = remember { context.harukaGraph.harukaPreferences.fetchOnAdd },
        title = stringResource(HR.strings.pref_fetch_on_add),
        subtitle = stringResource(HR.strings.pref_fetch_on_add_summary),
    )
}
