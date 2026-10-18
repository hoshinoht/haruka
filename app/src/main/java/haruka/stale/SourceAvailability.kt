package haruka.stale

import eu.kanade.tachiyomi.extension.model.Extension

/** Why a library source can't be used as-is, so its entries are worth migrating. */
enum class UnavailableReason {
    /** No installed extension provides the source; it only exists as a stub. */
    NotInstalled,

    /** The extension is installed but wasn't loaded (untrusted, unsupported lib, failed, ...). */
    NotLoaded,

    /** The extension still works but no configured store lists it any more, so it gets no updates. */
    Obsolete,
}

/**
 * Classifies sources against a snapshot of the extension state.
 *
 * A not-loaded extension never registers sources, so its source ids are only known from store
 * listings. A stub whose id no listing ties to a not-loaded package therefore counts as not installed.
 */
class SourceAvailability(
    loaded: Collection<Extension.Loaded>,
    notLoaded: Collection<Extension.NotLoaded>,
    available: Collection<Extension.Available>,
) {
    private val obsoleteSourceIds: Set<Long> = loaded
        .filter { it.isObsolete }
        .flatMapTo(HashSet()) { extension -> extension.sources.map { it.id } }

    private val notLoadedSourceIds: Set<Long> = notLoaded.mapTo(HashSet()) { it.pkgName }.let { packages ->
        available
            .filter { it.pkgName in packages }
            .flatMapTo(HashSet()) { extension -> extension.sources.map { it.id } }
    }

    /**
     * @param isStub whether the source manager has no registered source for [sourceId]
     * @return null when the source is usable
     */
    fun classify(sourceId: Long, isStub: Boolean): UnavailableReason? = when {
        !isStub -> UnavailableReason.Obsolete.takeIf { sourceId in obsoleteSourceIds }
        sourceId in notLoadedSourceIds -> UnavailableReason.NotLoaded
        else -> UnavailableReason.NotInstalled
    }
}
