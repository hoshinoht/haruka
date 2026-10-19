package haruka.sync.store

import haruka.sync.model.EntryKey
import kotlinx.serialization.Serializable

/**
 * Device-local sync bookkeeping, kept in `state.json` next to the base snapshots. Every field is null or empty
 * until the sync engine sets it, so a missing file reads as a device that never synced.
 */
@Serializable
data class SyncState(
    /** Epoch of the remote the base was merged with; a different remote epoch forces union mode. */
    val remoteEpoch: String? = null,
    /** Seq of the head last applied; a remote head below it is a rollback. */
    val baseCommitSeq: Long? = null,
    /** Seq of this device's last successful commit, checked against `devices[me].lastCommitSeq`. */
    val lastOwnCommitSeq: Long? = null,
    /** A commit whose CAS outcome is not known yet; resolved by lineage on the next sync. */
    val pendingCommit: PendingCommit? = null,
    /** Category uuid to the local category id it maps to on this device. */
    val categoryLocalIds: Map<String, Long> = emptyMap(),
    /** Removals held by the mass-removal guard until the user confirms or rejects them. */
    val pendingRemovals: List<PendingRemoval> = emptyList(),
    val lastSyncAtMillis: Long? = null,
    /** Name of the last sync outcome, as the sync engine reports it. */
    val lastResult: String? = null,
    /** The `haruka_last_restore_at` value already accounted for; a newer restore forces union mode. */
    val lastRestoreSeenAtMillis: Long? = null,
)

@Serializable
data class PendingCommit(
    val commitId: String,
    val commitSeq: Long,
)

@Serializable
data class PendingRemoval(
    val key: EntryKey,
    val origin: RemovalOrigin,
)

/** Where a held removal came from; confirming or rejecting it acts differently per side. */
@Serializable
enum class RemovalOrigin { LOCAL, REMOTE }
