package haruka.sync.engine

import haruka.sync.crypto.SyncKey
import haruka.sync.merge.MergeConflict
import haruka.sync.model.EntryKey
import haruka.sync.remote.SyncError
import haruka.sync.store.PendingRemoval

/** This install as the manifest's `devices` list records it. */
data class SyncDevice(val id: String, val model: String)

data class SyncRequest(
    /** The key of an encrypted remote, unlocked through `SyncEngine.unlock`; null syncs in plaintext. */
    val key: SyncKey? = null,
    /** `haruka_last_restore_at`; a Mihon restore newer than the last one seen forces union mode. */
    val lastRestoreAtMillis: Long? = null,
    /** Held removals the user confirmed: remote ones are applied locally, local ones are committed. */
    val confirmRemovals: Set<EntryKey> = emptySet(),
    /**
     * Held removals the user rejected: remote ones are re-added everywhere by the next sync, local ones are
     * re-favorited here (missing rows reinserted).
     */
    val rejectRemovals: Set<EntryKey> = emptySet(),
    /**
     * "Upload this device's library", the recovery from [SyncError.RemoteRolledBack]: skips the rollback checks,
     * union-merges this device with whatever the remote holds and commits it under a new remote epoch, so every
     * other device union-merges too.
     */
    val uploadUnderNewEpoch: Boolean = false,
)

sealed interface SyncOutcome {

    /** This device and the remote head agree; its state is the new base. */
    data class Synced(
        /** False when the merge changed nothing remotely and the commit was skipped. */
        val committed: Boolean,
        /** commitSeq of the head the new base was merged with. */
        val commitSeq: Long,
        /** Union mode: no usable base, so nothing was removed or deleted. */
        val union: Boolean,
        val uploadedFiles: Int,
        val downloadedFiles: Int,
        /** Entries inserted or updated locally. */
        val appliedEntries: Int,
        /** Settings both sides changed; this device's value was kept. */
        val conflicts: List<MergeConflict>,
        /** Removals held by the mass-removal guard, waiting for the user. */
        val pendingRemovals: List<PendingRemoval>,
    ) : SyncOutcome

    /** Nothing was committed or applied; the user (or an update) has to act first. */
    data class Stopped(val error: SyncError) : SyncOutcome

    /** Other devices kept committing first; every attempt lost its compare-and-swap. Retry later. */
    data class Contended(val attempts: Int) : SyncOutcome
}

sealed interface UnlockResult {
    data class Unlocked(val key: SyncKey) : UnlockResult

    /** The remote is plaintext or has no manifest yet; there is nothing to unlock. */
    data object NotEncrypted : UnlockResult

    data class Failed(val error: SyncError) : UnlockResult
}

enum class SyncPhase { CHECK, DOWNLOAD, MERGE, UPLOAD, COMMIT, APPLY, FINISH }

/** Progress and diagnostics of a sync; called on the syncing coroutine. */
interface SyncListener {
    fun onProgress(phase: SyncPhase, done: Int, total: Int) {}

    /** One line of timing or request-count diagnostics, prefixed `HarukaSync`; never contains secrets. */
    fun onLog(message: String) {}

    companion object {
        val NONE = object : SyncListener {}
    }
}

/**
 * Protocol step boundaries; tests throw from `SyncEngine.checkpoint` to simulate a process death right there. The
 * base store's own crash hook covers every file operation in between (staging, the `CURRENT` swap, state writes).
 */
internal enum class SyncCheckpoint { AFTER_UPLOAD, AFTER_CAS, AFTER_APPLY, AFTER_STAGE, AFTER_ACTIVATE }
