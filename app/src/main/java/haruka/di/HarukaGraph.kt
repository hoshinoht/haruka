package haruka.di

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import haruka.chapter.dedup.DedupSettings
import haruka.chapter.dedup.OfficialCatalogLoader
import haruka.library.LibraryToggle
import haruka.preference.HarukaPreferences
import haruka.sync.SyncManager
import haruka.updateerrors.UpdateErrorStore
import mihon.app.di.appGraph

/**
 * Haruka's graph accessors. Metro merges this interface into upstream's `AppGraph` as an extra supertype of the
 * generated graph, so `AppGraph.kt` stays byte-identical to upstream.
 */
@ContributesTo(AppScope::class)
interface HarukaGraph {
    val harukaPreferences: HarukaPreferences
    val libraryToggle: LibraryToggle
    val updateErrorStore: UpdateErrorStore
    val dedupSettings: DedupSettings
    val officialCatalogLoader: OfficialCatalogLoader
    val syncManager: SyncManager
}

val Context.harukaGraph: HarukaGraph get() = appGraph as HarukaGraph
