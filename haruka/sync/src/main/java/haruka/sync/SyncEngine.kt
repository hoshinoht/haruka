package haruka.sync

import haruka.sync.codec.SyncCodec
import haruka.sync.crypto.EncryptionCheck
import haruka.sync.crypto.SyncCryptoException
import haruka.sync.crypto.SyncKey
import haruka.sync.crypto.checkEncryption
import haruka.sync.engine.BucketPlan
import haruka.sync.engine.CorruptRemoteException
import haruka.sync.engine.CountingRemoteStore
import haruka.sync.engine.EncodedPayload
import haruka.sync.engine.LocalApplier
import haruka.sync.engine.LocalSource
import haruka.sync.engine.RemoteCodec
import haruka.sync.engine.SyncCheckpoint
import haruka.sync.engine.SyncDevice
import haruka.sync.engine.SyncListener
import haruka.sync.engine.SyncOutcome
import haruka.sync.engine.SyncPhase
import haruka.sync.engine.SyncRequest
import haruka.sync.engine.UnlockResult
import haruka.sync.engine.decodeStaged
import haruka.sync.engine.encodeStaged
import haruka.sync.engine.recordsSha
import haruka.sync.engine.withActual
import haruka.sync.engine.withInserted
import haruka.sync.engine.withPreMerge
import haruka.sync.merge.CategoryMerge
import haruka.sync.merge.CategoryMergeResult
import haruka.sync.merge.EntryField
import haruka.sync.merge.EntryMerge
import haruka.sync.merge.EntryMergeOptions
import haruka.sync.merge.EntryMergeResult
import haruka.sync.merge.EntryRemoval
import haruka.sync.merge.EntryUpdate
import haruka.sync.merge.LocalCategory
import haruka.sync.merge.LocalChangeSet
import haruka.sync.merge.MergeConflict
import haruka.sync.merge.PrefMerge
import haruka.sync.merge.PrefMergeResult
import haruka.sync.merge.RemovalOrigin
import haruka.sync.merge.isMassRemoval
import haruka.sync.model.BUCKET_COUNT
import haruka.sync.model.Bucket
import haruka.sync.model.BucketRef
import haruka.sync.model.CategoriesPayload
import haruka.sync.model.CategoryRecord
import haruka.sync.model.DeviceRecord
import haruka.sync.model.EntryKey
import haruka.sync.model.EntryRecord
import haruka.sync.model.FORMAT_VERSION
import haruka.sync.model.ManifestBody
import haruka.sync.model.ManifestHeader
import haruka.sync.model.PayloadRef
import haruka.sync.model.PrefsPayload
import haruka.sync.remote.BaseLineage
import haruka.sync.remote.CasResult
import haruka.sync.remote.Head
import haruka.sync.remote.HeaderCheck
import haruka.sync.remote.Manifest
import haruka.sync.remote.RemoteFile
import haruka.sync.remote.RemoteFileGoneException
import haruka.sync.remote.RemoteLayout
import haruka.sync.remote.RemoteRef
import haruka.sync.remote.RemoteStore
import haruka.sync.remote.SyncError
import haruka.sync.remote.checkHeader
import haruka.sync.remote.checkOwnCommit
import haruka.sync.remote.deviceOf
import haruka.sync.remote.newCommitId
import haruka.sync.remote.newRemoteEpoch
import haruka.sync.store.BaseSnapshot
import haruka.sync.store.BaseStore
import haruka.sync.store.PendingCommit
import haruka.sync.store.PendingRemoval
import haruka.sync.store.SyncState
import haruka.sync.store.baseBucketOf
import haruka.sync.store.toMergeBase
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.IOException
import java.util.TreeMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import haruka.sync.store.BaseBucket as StoredBucket
import haruka.sync.store.RemovalOrigin as StoredRemovalOrigin

/**
 * One device's side of the commit protocol (plan section "Commit protocol"): read the head, check it, download the
 * payloads whose content the base lacks, merge three-way with this device's library, commit through
 * compare-and-swap, apply the result locally through [applier], and make it the new base.
 *
 * Crash safety comes from ordering. Before the commit only `pendingCommit` changes locally; the DB apply runs
 * before the new base is staged and `CURRENT` swapped, so a death anywhere leaves the old base, against which applied
 * fields merge as no-ops and the rest re-applies. The base's lineage (epoch, commitSeq) is the active snapshot's
 * header, so `CURRENT` and `state.json` never have to change together.
 *
 * A staged base body is the head's body except where the staged records differ from the head's (held remote
 * removals, applier skips, unrealized fields): those refs carry the plaintext sha of what the base holds, so the
 * next sync downloads and merges that payload again instead of taking the base's copy for the remote's.
 *
 * Not thread-safe: the caller runs one sync at a time per [store].
 */
class SyncEngine(
    private val remote: RemoteStore,
    private val layout: RemoteLayout,
    private val store: BaseStore,
    private val local: LocalSource,
    private val applier: LocalApplier,
    private val device: SyncDevice,
    private val clock: () -> Long = System::currentTimeMillis,
    private val listener: SyncListener = SyncListener.NONE,
) {

    /** Test hook at protocol step boundaries; throwing from it simulates a process death there. */
    internal var checkpoint: (SyncCheckpoint) -> Unit = {}

    /**
     * Runs one sync. Network failures are thrown as [IOException] (retry later); everything else is the outcome.
     * A commit whose outcome is unknown is resolved by the next sync.
     */
    suspend fun sync(request: SyncRequest = SyncRequest()): SyncOutcome {
        val counting = CountingRemoteStore(remote)
        val started = TimeSource.Monotonic.markNow()
        var attempts = 0
        var outcome: SyncOutcome? = null
        while (outcome == null) {
            attempts++
            outcome = try {
                Attempt(counting, request).run()
            } catch (e: RemoteFileGoneException) {
                // Another device's commit superseded a payload while we read it: start over from its head.
                listener.onLog("HarukaSync restart reason=gone")
                null
            } catch (e: CorruptRemoteException) {
                SyncOutcome.Stopped(SyncError.Corrupt(e.message.orEmpty()))
            }
            if (outcome == null && attempts >= MAX_ATTEMPTS) outcome = SyncOutcome.Contended(attempts)
        }
        if (outcome !is SyncOutcome.Synced) {
            store.writeState(store.readState().copy(lastResult = outcome.resultName()))
        }
        listener.onProgress(SyncPhase.FINISH, 1, 1)
        listener.onLog(
            "HarukaSync timing phase=total ms=${started.elapsedNow().inWholeMilliseconds} " +
                "result=${outcome.resultName()} attempts=$attempts",
        )
        listener.onLog("HarukaSync requests ${counting.summary()}")
        return outcome
    }

    /**
     * The key of the encrypted remote if [passphrase] unlocks it. The key must also open the current manifest, so
     * tampered params report [SyncError.Corrupt] here instead of a key that fails on the next sync.
     */
    suspend fun unlock(passphrase: String): UnlockResult {
        val head = remote.readHead() ?: return UnlockResult.NotEncrypted
        return try {
            val file = RemoteCodec(layout, key = null).readManifestFile(head.bytes)
            if (file.header.formatVersion > FORMAT_VERSION) {
                return UnlockResult.Failed(SyncError.FormatTooNew(file.header.formatVersion))
            }
            val params = file.header.encryption ?: return UnlockResult.NotEncrypted
            val key = SyncKey.unlock(params, passphrase)
                ?: return UnlockResult.Failed(SyncError.WrongPassphrase(params.keyId))
            RemoteCodec(layout, key).openBody(file)
            UnlockResult.Unlocked(key)
        } catch (e: SyncCryptoException) {
            UnlockResult.Failed(SyncError.Corrupt(e.message.orEmpty()))
        } catch (e: CorruptRemoteException) {
            UnlockResult.Failed(SyncError.Corrupt(e.message.orEmpty()))
        }
    }

    /** One pass from reading the head to activating the new base; [run] returns null to start over. */
    private inner class Attempt(private val remote: CountingRemoteStore, private val request: SyncRequest) {
        private val key = request.key
        private val codec = RemoteCodec(layout, key)
        private val now = clock()

        suspend fun run(): SyncOutcome? {
            var mark: TimeMark = TimeSource.Monotonic.markNow()
            listener.onProgress(SyncPhase.CHECK, 0, 1)
            val listing = remote.list()
            val head = remote.readHead() ?: initialize() ?: return null
            val file = codec.readManifestFile(head.bytes)
            val state = store.readState()
            val lineage = lineageOf(store.current, state)
            // The recovery upload replaces whatever lineage the remote shows; only the format and key gates apply.
            val check = checkHeader(file.header, lineage.takeUnless { request.uploadUnderNewEpoch }, key?.keyId)
            if (check is HeaderCheck.Rejected) return SyncOutcome.Stopped(check.error)
            if (checkEncryption(file.header, key) == EncryptionCheck.RemotePlaintext) {
                return SyncOutcome.Stopped(SyncError.RemotePlaintext)
            }
            val manifest = codec.openBody(file)
            if (check == HeaderCheck.ThreeWay) {
                checkOwnCommit(manifest.body, device.id, lineage!!)?.let { return SyncOutcome.Stopped(it) }
            }

            val headEpoch = manifest.header.remoteEpoch
            var ownSeq = state.lastOwnCommitSeq.takeIf { state.remoteEpoch == headEpoch }
            state.pendingCommit?.let { pending ->
                // Landed or not, the old base stays: a landed commit re-merges as a no-op plus the remote changes.
                val landed = manifest.body.commitId == pending.commitId ||
                    manifest.body.deviceOf(device.id)?.lastCommitId == pending.commitId
                if (landed) ownSeq = maxOf(ownSeq ?: pending.commitSeq, pending.commitSeq)
            }

            val restored = request.lastRestoreAtMillis != null &&
                request.lastRestoreAtMillis > (state.lastRestoreSeenAtMillis ?: Long.MIN_VALUE)
            val union = check == HeaderCheck.Union || restored
            val base = store.current.takeUnless { union }
            listener.onProgress(SyncPhase.CHECK, 1, 1)
            mark = logTiming("check", mark, "union=$union seq=${manifest.header.commitSeq}")

            val downloaded = download(manifest.body, base)
            mark = logTiming("download", mark, "files=${downloaded.size}")

            val merge = Merge(manifest.body, base, downloaded, state)
            merge.compute()
            mark = logTiming("merge", mark)

            val dirty = request.uploadUnderNewEpoch ||
                merge.categories.remoteDirty ||
                merge.prefs.remoteDirty ||
                merge.plans.any { it!!.remoteDirty }
            var committedHeader: ManifestHeader? = null
            var committedBody: ManifestBody? = null
            var uploadedFiles = 0
            if (dirty) {
                val uploads = merge.uploads()
                listener.onProgress(SyncPhase.UPLOAD, 0, uploads.size)
                val done = AtomicInteger()
                val ids = concurrently(uploads) {
                    remote.createImmutable(it.file, it.stored).also {
                        listener.onProgress(SyncPhase.UPLOAD, done.incrementAndGet(), uploads.size)
                    }
                }
                uploadedFiles = ids.size
                val uploadedRefs = uploads.indices.associate { uploads[it] to uploads[it].ref(ids[it], layout) }
                checkpoint(SyncCheckpoint.AFTER_UPLOAD)
                mark = logTiming("upload", mark, "files=${ids.size}")

                listener.onProgress(SyncPhase.COMMIT, 0, 1)
                val epoch = if (request.uploadUnderNewEpoch) newRemoteEpoch() else headEpoch
                val header = ManifestHeader(FORMAT_VERSION, epoch, manifest.header.commitSeq + 1, key?.params)
                val body = merge.newBody(newCommitId(), header.commitSeq, uploadedRefs)
                val pending = PendingCommit(body.commitId, header.commitSeq)
                store.writeState(state.copy(pendingCommit = pending))
                val landed = when (val cas = remote.casHead(head, codec.encodeManifest(Manifest(header, body)))) {
                    CasResult.Committed -> true
                    CasResult.Conflict -> {
                        // Definitely not written, so nothing can reference these payloads.
                        deleteQuietly(ids)
                        false
                    }
                    is CasResult.Ambiguous -> {
                        listener.onLog("HarukaSync cas=ambiguous cause=${cas.cause.javaClass.simpleName}")
                        // A request that is still in flight may land later, so its payloads are left to the sweep.
                        landedByLineage(body.commitId)
                    }
                }
                if (!landed) {
                    listener.onLog("HarukaSync restart reason=cas")
                    return null
                }
                checkpoint(SyncCheckpoint.AFTER_CAS)
                ownSeq = header.commitSeq
                store.writeState(
                    state.copy(
                        remoteEpoch = epoch,
                        baseCommitSeq = header.commitSeq,
                        lastOwnCommitSeq = header.commitSeq,
                        pendingCommit = pending,
                    ),
                )
                committedHeader = header
                committedBody = body
                listener.onProgress(SyncPhase.COMMIT, 1, 1)
                mark = logTiming("commit", mark, "seq=${header.commitSeq}")
            }

            val applied = merge.applyLocally()
            mark = logTiming("apply", mark, "entries=${applied.entries}")

            val stagedHeader = committedHeader ?: manifest.header
            merge.stageAndActivate(stagedHeader, committedBody ?: manifest.body, applied)
            checkpoint(SyncCheckpoint.AFTER_ACTIVATE)
            val pendingRemovals = merge.pendingRemovals()
            store.writeState(
                SyncState(
                    remoteEpoch = stagedHeader.remoteEpoch,
                    baseCommitSeq = stagedHeader.commitSeq,
                    lastOwnCommitSeq = ownSeq,
                    pendingCommit = null,
                    categoryLocalIds = applied.categoryLocalIds,
                    pendingRemovals = pendingRemovals,
                    lastSyncAtMillis = now,
                    lastResult = RESULT_SYNCED,
                    lastRestoreSeenAtMillis = maxOfNullable(state.lastRestoreSeenAtMillis, request.lastRestoreAtMillis),
                ),
            )
            mark = logTiming("activate", mark, "seq=${stagedHeader.commitSeq}")

            collectGarbage(listing, head, manifest.body, committedBody)
            logTiming("gc", mark)

            return SyncOutcome.Synced(
                committed = committedHeader != null,
                commitSeq = stagedHeader.commitSeq,
                union = union,
                uploadedFiles = uploadedFiles,
                downloadedFiles = downloaded.size,
                appliedEntries = applied.entries,
                conflicts = merge.conflicts(),
                pendingRemovals = pendingRemovals,
            )
        }

        /** Creates the first manifest of an empty remote (one device wins a race), then reads the head again. */
        private suspend fun initialize(): Head? {
            val initial = Manifest.initial(device.id, now)
            val manifest = initial.copy(header = initial.header.copy(encryption = key?.params))
            val result = remote.casHead(null, codec.encodeManifest(manifest))
            listener.onLog("HarukaSync init result=${result.javaClass.simpleName}")
            return remote.readHead()
        }

        /** After an ambiguous CAS: our commit landed if the head is it or lists it as this device's last commit. */
        private suspend fun landedByLineage(commitId: String): Boolean {
            val head = remote.readHead() ?: return false
            val file = codec.readManifestFile(head.bytes)
            if (file.header.encryption?.keyId != key?.keyId) return false
            val body = codec.openBody(file).body
            return body.commitId == commitId || body.deviceOf(device.id)?.lastCommitId == commitId
        }

        /** Every payload of the head whose content the base does not hold (all of them without a base). */
        private suspend fun download(body: ManifestBody, base: BaseSnapshot?): Map<String, ByteArray> {
            val baseShas = base?.body?.buckets?.associate { it.index to it.payload.plaintextSha }.orEmpty()
            val wanted = ArrayList<PayloadRef>()
            for (ref in body.buckets) {
                if (base == null || baseShas[ref.index] != ref.payload.plaintextSha) wanted += ref.payload
            }
            body.categoriesRef?.takeIf { base == null || base.body.categoriesRef?.plaintextSha != it.plaintextSha }
                ?.let(wanted::add)
            body.prefsRef?.takeIf { base == null || base.body.prefsRef?.plaintextSha != it.plaintextSha }
                ?.let(wanted::add)
            listener.onProgress(SyncPhase.DOWNLOAD, 0, wanted.size)
            val done = AtomicInteger()
            val bytes = concurrently(wanted) {
                val stored = try {
                    remote.download(it.fileId)
                } catch (e: RemoteFileGoneException) {
                    // A rolled-back remote may name payloads that were collected since; the recovery upload
                    // merges what is left and drops the dangling refs. Elsewhere a gone file means start over.
                    if (!request.uploadUnderNewEpoch) throw e
                    null
                }
                listener.onProgress(SyncPhase.DOWNLOAD, done.incrementAndGet(), wanted.size)
                stored
            }
            val out = HashMap<String, ByteArray>()
            for (i in wanted.indices) bytes[i]?.let { out[wanted[i].fileId] = it }
            return out
        }

        /**
         * Deletes what nobody references any more: payloads only the replaced head referenced, manifests that lost
         * an initialization race, and orphans (failed uploads, lost commits) once they are an hour old by both this
         * device's clock and the newest file's server time, so a commit still in flight elsewhere keeps its
         * payloads. Best effort: what fails stays for the next sync.
         */
        private suspend fun collectGarbage(
            listing: List<RemoteRef>,
            head: Head,
            replaced: ManifestBody,
            committed: ManifestBody?,
        ) {
            val referenced = replaced.fileIds()
            val live = committed?.fileIds() ?: referenced
            val garbage = LinkedHashSet<String>()
            if (committed != null) referenced.filterTo(garbage) { it !in live }
            val newest = listing.maxOfOrNull { it.createdAtMillis } ?: 0
            val cutoff = minOf(now, newest) - ORPHAN_AGE_MILLIS
            for (ref in listing) {
                when (layout.parse(ref.name)) {
                    RemoteFile.Manifest -> if (ref.id != head.fileId) garbage += ref.id
                    is RemoteFile.Payload -> {
                        if (ref.id !in live && ref.id !in referenced && ref.createdAtMillis <= cutoff) garbage += ref.id
                    }
                    null -> Unit
                }
            }
            if (garbage.isNotEmpty()) listener.onLog("HarukaSync gc files=${garbage.size}")
            deleteQuietly(garbage)
        }

        private suspend fun deleteQuietly(ids: Collection<String>) {
            try {
                for (id in ids) remote.delete(id)
            } catch (e: IOException) {
                listener.onLog("HarukaSync delete failed=${e.javaClass.simpleName}")
            }
        }

        /**
         * The merge of one attempt and everything derived from it, up to the activated base. [base] is the base
         * to merge against: null in union mode, where nothing is removed or deleted.
         */
        private inner class Merge(
            private val head: ManifestBody,
            private val base: BaseSnapshot?,
            private val downloaded: Map<String, ByteArray>,
            private val state: SyncState,
        ) {
            val plans = arrayOfNulls<BucketPlan>(BUCKET_COUNT)
            lateinit var categories: CategoryMergeResult
            lateinit var prefs: PrefMergeResult
            private var baseCategories = CategoriesPayload()
            private var basePrefs = PrefsPayload()
            private var localCategoryIds = emptyMap<String, Long>()
            private val remoteRefs = head.buckets.associate { it.index to it.payload }

            /** Without a base every payload was downloaded; one that wasn't is gone (recovery upload only). */
            private fun available(ref: PayloadRef) = base != null || ref.fileId in downloaded

            fun conflicts(): List<MergeConflict> =
                categories.conflicts + prefs.conflicts + plans.flatMap { it!!.conflicts }

            /** Held removals awaiting the user; rejected ones are resolved by this sync. */
            fun pendingRemovals(): List<PendingRemoval> = plans.flatMap { it!!.held }
                .filter { it.key !in request.rejectRemovals }
                .map { PendingRemoval(it.key, StoredRemovalOrigin.valueOf(it.origin.name)) }

            suspend fun compute() {
                val remoteCategories = head.categoriesRef?.let { ref ->
                    downloaded[ref.fileId]?.let { codec.decodeCategories(ref, it) } ?: base?.readCategories()
                }
                val remotePrefs = head.prefsRef?.let { ref ->
                    downloaded[ref.fileId]?.let { codec.decodePrefs(ref, it) } ?: base?.readPrefs()
                }
                if (base != null) {
                    baseCategories = base.readCategories()
                    basePrefs = base.readPrefs()
                }

                val rows = local.readCategories()
                val uuidOfId = state.categoryLocalIds.entries.associate { (uuid, id) -> id to uuid }
                val assigned = CategoryMerge.assignUuids(
                    rows.map { LocalCategory(it.id, uuidOfId[it.id], it.name) },
                    remoteCategories,
                )
                localCategoryIds = assigned.entries.associate { (id, uuid) -> uuid to id }
                categories = CategoryMerge.merge(
                    base = base?.let { baseCategories },
                    local = rows.map { CategoryRecord(assigned.getValue(it.id), it.name, it.order, it.flags) },
                    remote = remoteCategories,
                    nowMillis = now,
                )
                prefs = PrefMerge.merge(base?.let { basePrefs }, local.readPrefs(), remotePrefs)

                mergeEntries(assigned)
            }

            /**
             * Merges every bucket with the guard off and counts removals library-wide, then re-merges the buckets
             * holding removals the guard (or the user's rejection) holds back.
             */
            private suspend fun mergeEntries(categoryUuids: Map<Long, String>) {
                val options = EntryMergeOptions(
                    nowMillis = now,
                    categoryRemap = categories.remap,
                    deletedCategories = categories.deleted,
                    confirmedRemovals = request.confirmRemovals,
                )
                val withRemovals = HashMap<Int, Inputs>()
                var localFavorites = 0
                var remoteFavorites = 0
                listener.onProgress(SyncPhase.MERGE, 0, BUCKET_COUNT)
                for (index in 0..<BUCKET_COUNT) {
                    val stored = base?.readBucket(index)
                    val remoteBucket = remoteBucket(index, stored)
                    val known = HashSet<EntryKey>()
                    stored?.entries?.forEach { known += it.record.key }
                    remoteBucket?.entries?.forEach { known += it.key }
                    val localEntries = local.readBucket(index, known, categoryUuids)
                    localFavorites += localEntries.count { it.favorite }
                    remoteFavorites += remoteBucket?.entries?.count { it.favorite } ?: 0
                    val result =
                        EntryMerge.mergeBucket(index, stored?.toMergeBase(), localEntries, remoteBucket, options)
                    if (result.removals.isNotEmpty()) {
                        withRemovals[index] = Inputs(stored, localEntries, remoteBucket, result.removals)
                    }
                    plans[index] = plan(result, stored, localEntries)
                    listener.onProgress(SyncPhase.MERGE, index + 1, BUCKET_COUNT)
                }

                val counted = withRemovals.values.flatMap { it.removals }.filter { it.key !in request.confirmRemovals }
                val hold = HashSet<EntryKey>()
                for (origin in RemovalOrigin.entries) {
                    val removals = counted.filter { it.origin == origin }
                    // Each direction is measured against the library it would shrink.
                    val library = if (origin == RemovalOrigin.LOCAL) remoteFavorites else localFavorites
                    if (isMassRemoval(removals.size, library)) removals.mapTo(hold) { it.key }
                }
                counted.filter { it.key in request.rejectRemovals }.mapTo(hold) { it.key }
                if (hold.isEmpty()) return
                listener.onLog("HarukaSync guard held=${hold.size} removals=${counted.size}")
                for ((index, inputs) in withRemovals) {
                    val keys = inputs.removals.mapTo(HashSet()) { it.key }
                    if (keys.none(hold::contains)) continue
                    // Holding per key: the bucket's removals outside [hold] pass as confirmed.
                    val held = options.copy(
                        holdLocalRemovals = true,
                        holdRemoteRemovals = true,
                        confirmedRemovals = request.confirmRemovals + (keys - hold),
                    )
                    val result =
                        EntryMerge.mergeBucket(index, inputs.base?.toMergeBase(), inputs.local, inputs.remote, held)
                    plans[index] = plan(result, inputs.base, inputs.local)
                }
            }

            /** The remote bucket: decoded if downloaded, else the base's records (same plaintext sha), else none. */
            private fun remoteBucket(index: Int, stored: StoredBucket?): Bucket? {
                val ref = remoteRefs[index] ?: return null
                val bytes = downloaded[ref.fileId]
                    ?: return stored?.let { bucket -> Bucket(index, bucket.entries.map { it.record }) }
                return codec.decodeBucket(index, ref, bytes)
            }

            private fun plan(
                result: EntryMergeResult,
                stored: StoredBucket?,
                localEntries: List<EntryRecord>,
            ): BucketPlan {
                val index = result.merged.index
                val heldRemote = result.heldRemovals
                    .filter { it.origin == RemovalOrigin.REMOTE && it.key !in request.rejectRemovals }
                    .mapTo(HashSet()) { it.key }
                // A held remote removal is committed but not applied: the base keeps the pre-merge entry, so the
                // guard fires again next time. A rejected one stages the removal; the next sync re-adds the entry.
                val staged = baseBucketOf(result.merged, result.localPresent).withPreMerge(heldRemote, stored)
                val upload = if (result.remoteDirty && result.merged.entries.isNotEmpty()) {
                    codec.encodeBucket(result.merged)
                } else {
                    null
                }
                val inserts = result.localChanges.inserts.mapTo(ArrayList()) { it.key }
                // Existing rows never gain chapters through sync, so updates don't need the merged index.
                val updates = result.localChanges.updates.mapTo(ArrayList()) {
                    it.copy(entry = it.entry.copy(chapters = emptyList()))
                }
                refavoriteRejected(result, localEntries, inserts, updates)
                val carry = base != null && heldRemote.isEmpty() && staged == stored
                return BucketPlan(
                    index = index,
                    remoteDirty = result.remoteDirty,
                    upload = upload,
                    staged = if (carry) null else encodeStaged(staged),
                    heldRemote = heldRemote.isNotEmpty(),
                    insertKeys = inserts,
                    updates = updates,
                    held = result.heldRemovals,
                    conflicts = result.conflicts,
                )
            }

            /** A rejected local removal re-favorites the entry here; a missing row is reinserted. */
            private fun refavoriteRejected(
                result: EntryMergeResult,
                localEntries: List<EntryRecord>,
                inserts: MutableList<EntryKey>,
                updates: MutableList<EntryUpdate>,
            ) {
                for (removal in result.heldRemovals) {
                    if (removal.origin != RemovalOrigin.LOCAL || removal.key !in request.rejectRemovals) continue
                    // A held local removal stays favorite in the merge.
                    val merged = result.merged.entries.first { it.key == removal.key }
                    if (localEntries.none { it.key == removal.key }) {
                        inserts += removal.key
                        continue
                    }
                    val i = updates.indexOfFirst { it.entry.key == removal.key }
                    if (i < 0) {
                        val entry = merged.copy(chapters = emptyList())
                        updates += EntryUpdate(entry, setOf(EntryField.FAVORITE), emptyList(), emptyList(), emptyList())
                    } else {
                        val update = updates[i]
                        updates[i] = update.copy(
                            entry = update.entry.copy(
                                favorite = true,
                                favoriteAtMillis = merged.favoriteAtMillis,
                                removedAtMillis = null,
                            ),
                            fields = update.fields + EntryField.FAVORITE,
                        )
                    }
                }
            }

            /** The payloads this commit uploads: changed buckets, then categories and prefs if they changed. */
            fun uploads(): List<EncodedPayload> {
                val out = plans.mapNotNullTo(ArrayList()) { it!!.upload }
                if (categories.remoteDirty && categories.merged.categories.isNotEmpty()) {
                    out += codec.encodeCategories(categories.merged)
                }
                if (prefs.remoteDirty && prefs.merged.prefs.isNotEmpty()) out += codec.encodePrefs(prefs.merged)
                return out
            }

            /** The new head's body: uploaded refs for changed payloads, the replaced head's refs for the rest. */
            fun newBody(commitId: String, commitSeq: Long, uploaded: Map<EncodedPayload, PayloadRef>): ManifestBody {
                val buckets = plans.mapNotNull { plan ->
                    val ref = when {
                        plan!!.upload != null -> uploaded.getValue(plan.upload)
                        plan.remoteDirty -> null
                        else -> remoteRefs[plan.index]?.takeIf(::available)
                    }
                    ref?.let { BucketRef(plan.index, it) }
                }
                fun uploadedOf(kind: Class<out RemoteFile.Payload>): PayloadRef? =
                    uploaded.entries.firstOrNull { kind.isInstance(it.key.file) }?.value
                val me = DeviceRecord(device.id, device.model, commitId, commitSeq, lastSyncAtMillis = now)
                return ManifestBody(
                    commitId = commitId,
                    writerDeviceId = device.id,
                    writtenAtMillis = now,
                    bucketCount = BUCKET_COUNT,
                    buckets = buckets,
                    categoriesRef = if (categories.remoteDirty) {
                        uploadedOf(RemoteFile.Categories::class.java)
                    } else {
                        head.categoriesRef?.takeIf(::available)
                    },
                    prefsRef = if (prefs.remoteDirty) {
                        uploadedOf(RemoteFile.Prefs::class.java)
                    } else {
                        head.prefsRef?.takeIf(::available)
                    },
                    devices = head.devices.filter { it.id != device.id } + me,
                )
            }

            /** Applies the merge locally in the plan's order and collects the applier's reports. */
            suspend fun applyLocally(): Applied {
                val total = plans.count { it!!.hasLocalChanges }
                listener.onProgress(SyncPhase.APPLY, 0, total)
                var categoryIds = localCategoryIds
                var categoriesSkipped = false
                if (!categories.localChanges.isEmpty) {
                    val result = applier.applyCategories(categories.localChanges, categoryIds)
                    categoryIds = result.localIds
                    categoriesSkipped = result.skipped
                }
                var entries = 0
                var done = 0
                for (plan in plans) {
                    if (!plan!!.hasLocalChanges) continue
                    val inserts = if (plan.insertKeys.isEmpty()) {
                        emptyList()
                    } else {
                        val records = stagedSource(plan).entries.associateBy { it.record.key }
                        plan.insertKeys.map { records.getValue(it).record }
                    }
                    val result = applier.applyEntries(LocalChangeSet(inserts, plan.updates), categoryIds)
                    plan.skipped = result.skipped
                    plan.unrealized = result.unrealized - result.skipped
                    entries += inserts.size + plan.updates.size
                    listener.onProgress(SyncPhase.APPLY, ++done, total)
                }
                val prefsSkipped = !prefs.localChanges.isEmpty && applier.applyPrefs(prefs.localChanges).skipped
                checkpoint(SyncCheckpoint.AFTER_APPLY)
                return Applied(entries, categoryIds, categoriesSkipped, prefsSkipped)
            }

            /**
             * Stages the merged state patched with what the apply did not do (skips keep the pre-merge base,
             * unrealized entries take the actual local state, inserted chapters become present) and activates it.
             */
            fun stageAndActivate(header: ManifestHeader, headBody: ManifestBody, applied: Applied) {
                val patched = HashMap<Int, StoredBucket>()
                for (plan in plans) if (plan!!.recordsPatched) patched[plan.index] = finalBucket(plan)!!
                val stagedCategories = if (applied.categoriesSkipped) baseCategories else categories.merged
                val stagedPrefs = if (applied.prefsSkipped) basePrefs else prefs.merged
                val body = baseBody(
                    headBody,
                    patched,
                    stagedCategories.takeIf { applied.categoriesSkipped },
                    stagedPrefs.takeIf { applied.prefsSkipped },
                )
                val staged = store.stage(header, body)
                for (plan in plans) {
                    val bucket = patched[plan!!.index] ?: finalBucket(plan)
                    if (bucket == null) staged.carryBucket(base!!, plan.index) else staged.writeBucket(bucket)
                }
                staged.writeCategories(stagedCategories)
                staged.writePrefs(stagedPrefs)
                checkpoint(SyncCheckpoint.AFTER_STAGE)
                store.activate(staged)
            }

            /** The bucket to stage for [plan], or null to carry the base's file unchanged. */
            private fun finalBucket(plan: BucketPlan): StoredBucket? {
                val inserted = plan.insertKeys.filter { it !in plan.skipped && it !in plan.unrealized }
                if (plan.staged == null && inserted.isEmpty() && !plan.recordsPatched) return null
                var bucket = stagedSource(plan).withInserted(inserted)
                if (plan.skipped.isNotEmpty()) bucket = bucket.withPreMerge(plan.skipped, base?.readBucket(plan.index))
                return bucket.withActual(plan.unrealized)
            }

            private fun stagedSource(plan: BucketPlan): StoredBucket =
                plan.staged?.let(::decodeStaged) ?: base!!.readBucket(plan.index)
        }
    }

    private class Inputs(
        val base: StoredBucket?,
        val local: List<EntryRecord>,
        val remote: Bucket?,
        val removals: List<EntryRemoval>,
    )

    private class Applied(
        /** Entries inserted or updated. */
        val entries: Int,
        val categoryLocalIds: Map<String, Long>,
        val categoriesSkipped: Boolean,
        val prefsSkipped: Boolean,
    )

    private fun logTiming(phase: String, mark: TimeMark, extra: String = ""): TimeMark {
        val ms = mark.elapsedNow().inWholeMilliseconds
        listener.onLog("HarukaSync timing phase=$phase ms=$ms" + if (extra.isEmpty()) "" else " $extra")
        return TimeSource.Monotonic.markNow()
    }

    private suspend fun <T, R> concurrently(items: List<T>, block: suspend (T) -> R): List<R> = coroutineScope {
        val permits = Semaphore(TRANSFER_CONCURRENCY)
        items.map { async { permits.withPermit { block(it) } } }.awaitAll()
    }

    private companion object {
        /** Compare-and-swap rounds before giving up for now (plan: 412 restarts at most 3 times). */
        const val MAX_ATTEMPTS = 3

        /** Parallel payload transfers; Drive batch requests don't carry media. */
        const val TRANSFER_CONCURRENCY = 6

        /** Unreferenced payloads younger than this may belong to a commit still in flight elsewhere. */
        const val ORPHAN_AGE_MILLIS = 60 * 60 * 1000L
    }
}

/**
 * What the base remembers of its lineage. Its epoch and seq are the active snapshot's header; `state.json` may be
 * ahead after a CAS whose base was not activated yet, and its own-commit seq only counts within the same epoch.
 */
private fun lineageOf(base: BaseSnapshot?, state: SyncState): BaseLineage? {
    base ?: return null
    val epoch = base.header.remoteEpoch
    val sameEpoch = state.remoteEpoch == epoch
    return BaseLineage(
        remoteEpoch = epoch,
        baseCommitSeq = maxOf(base.header.commitSeq, state.baseCommitSeq?.takeIf { sameEpoch } ?: Long.MIN_VALUE),
        lastOwnCommitSeq = state.lastOwnCommitSeq.takeIf { sameEpoch },
    )
}

/** [body] with the refs of [patched] buckets, categories and prefs describing the content the base holds. */
private fun baseBody(
    body: ManifestBody,
    patched: Map<Int, StoredBucket>,
    categories: CategoriesPayload?,
    prefs: PrefsPayload?,
): ManifestBody {
    if (patched.isEmpty() && categories == null && prefs == null) return body
    val refs = body.buckets.associateTo(TreeMap()) { it.index to it.payload }
    for ((index, bucket) in patched) {
        if (bucket.entries.isEmpty()) {
            refs.remove(index)
        } else {
            refs[index] = (refs[index] ?: BASE_ONLY_REF)
                .copy(plaintextSha = bucket.recordsSha(), entryCount = bucket.entries.size)
        }
    }
    return body.copy(
        buckets = refs.map { (index, ref) -> BucketRef(index, ref) },
        categoriesRef = categories?.let {
            baseRef(body.categoriesRef, SyncCodec.encodeCategories(it), it.categories.size)
        } ?: body.categoriesRef,
        prefsRef = prefs?.let { baseRef(body.prefsRef, SyncCodec.encodePrefs(it), it.prefs.size) } ?: body.prefsRef,
    )
}

private fun baseRef(ref: PayloadRef?, plaintext: ByteArray, count: Int): PayloadRef? =
    if (count == 0) {
        null
    } else {
        (ref ?: BASE_ONLY_REF).copy(plaintextSha = SyncCodec.sha256Hex(plaintext), entryCount = count)
    }

/** A ref for base content the remote has no payload of; only in base bodies, never uploaded or deleted. */
private val BASE_ONLY_REF = PayloadRef(fileId = "", name = "", storedSha = "", plaintextSha = "", entryCount = 0)

private const val RESULT_SYNCED = "Synced"

private fun ManifestBody.fileIds(): Set<String> {
    val ids = buckets.mapTo(HashSet()) { it.payload.fileId }
    categoriesRef?.let { ids += it.fileId }
    prefsRef?.let { ids += it.fileId }
    return ids
}

private fun maxOfNullable(a: Long?, b: Long?): Long? = when {
    a == null -> b
    b == null -> a
    else -> maxOf(a, b)
}

private fun SyncOutcome.resultName(): String = when (this) {
    is SyncOutcome.Synced -> RESULT_SYNCED
    is SyncOutcome.Stopped -> error.javaClass.simpleName
    is SyncOutcome.Contended -> "Contended"
}
