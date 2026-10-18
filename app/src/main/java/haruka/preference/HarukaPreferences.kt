package haruka.preference

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

/** Haruka-only preferences. Keys are prefixed `haruka_` so they never collide with upstream keys. */
@Inject
@SingleIn(AppScope::class)
class HarukaPreferences(preferenceStore: PreferenceStore) {

    val panoramaCovers: Preference<Boolean> = preferenceStore.getBoolean("haruka_panorama_covers", true)

    val groupUpdates: Preference<Boolean> = preferenceStore.getBoolean("haruka_group_updates", true)

    val relatedManga: Preference<Boolean> = preferenceStore.getBoolean("haruka_related_manga", true)
}
