package haruka.sync.remote

import haruka.sync.model.BUCKET_COUNT
import haruka.sync.model.DeviceRecord
import haruka.sync.model.FORMAT_VERSION
import haruka.sync.model.ManifestBody
import haruka.sync.model.ManifestHeader
import java.util.UUID

/** A decoded manifest: the plaintext header and the (decrypted) body. */
data class Manifest(val header: ManifestHeader, val body: ManifestBody) {

    companion object {
        /**
         * The manifest written when the remote has none: a fresh [remoteEpoch], commitSeq 0, no payloads and no
         * devices. A device enters `devices` with its first real commit.
         */
        fun initial(writerDeviceId: String, nowMillis: Long, remoteEpoch: String = newRemoteEpoch()) = Manifest(
            header = ManifestHeader(formatVersion = FORMAT_VERSION, remoteEpoch = remoteEpoch, commitSeq = 0),
            body = ManifestBody(
                commitId = newCommitId(),
                writerDeviceId = writerDeviceId,
                writtenAtMillis = nowMillis,
                bucketCount = BUCKET_COUNT,
                buckets = emptyList(),
                devices = emptyList(),
            ),
        )
    }
}

/**
 * A new random remote epoch. Used for the initial manifest, "Reset remote" and the RemoteRolledBack recovery
 * upload only; re-encryption keeps the epoch, so devices keep their three-way base.
 */
fun newRemoteEpoch(): String = UUID.randomUUID().toString()

/** A new random commit id; lets a device recognise its own commit after an ambiguous compare-and-swap. */
fun newCommitId(): String = UUID.randomUUID().toString()

/** The lineage record of device [id], or null if that device never committed (or was removed in settings). */
fun ManifestBody.deviceOf(id: String): DeviceRecord? = devices.find { it.id == id }
