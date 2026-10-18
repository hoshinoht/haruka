package haruka.stale

import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.ui.browse.migration.sources.MigrateSourceViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import tachiyomi.domain.manga.interactor.GetFavorites

@Inject
class StaleSources(
    private val extensionManager: ExtensionManager,
    private val getFavorites: GetFavorites,
) {

    fun availability(): Flow<SourceAvailability> = combine(
        extensionManager.loadedExtensionsFlow,
        extensionManager.notLoadedExtensionsFlow,
        extensionManager.availableExtensionsFlow,
    ) { loaded, notLoaded, available -> SourceAvailability(loaded, notLoaded, available) }

    /** Library entries of [sourceIds], grouped in the given source order and by title within a source. */
    suspend fun favoriteIds(sourceIds: List<Long>): List<Long> {
        val order = sourceIds.withIndex().associate { (index, id) -> id to index }
        return getFavorites.await()
            .filter { it.source in order }
            .sortedWith(compareBy({ order.getValue(it.source) }, { it.title.lowercase() }))
            .map { it.id }
    }
}

/** Marks the unavailable sources and moves them to the top, keeping the user's sort within each group. */
fun markUnavailable(
    state: MigrateSourceViewModel.State,
    availability: SourceAvailability,
): MigrateSourceViewModel.State {
    val unavailable = state.items
        .mapNotNull { (source, _) -> availability.classify(source.id, source.isStub)?.let { source.id to it } }
        .toMap()
    return state.copy(
        items = state.items.sortedBy { (source, _) -> source.id !in unavailable },
        unavailable = unavailable,
    )
}
