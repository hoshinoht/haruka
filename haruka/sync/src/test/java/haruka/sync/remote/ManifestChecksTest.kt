package haruka.sync.remote

import haruka.sync.model.DeviceRecord
import haruka.sync.model.EncryptionParams
import haruka.sync.model.FORMAT_VERSION
import haruka.sync.model.ManifestBody
import haruka.sync.model.ManifestHeader
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ManifestChecksTest {

    private val header = ManifestHeader(formatVersion = FORMAT_VERSION, remoteEpoch = "e1", commitSeq = 10)
    private val base = BaseLineage(remoteEpoch = "e1", baseCommitSeq = 10, lastOwnCommitSeq = 8)
    private val encrypted = header.copy(
        encryption = EncryptionParams("k1", "PBKDF2WithHmacSHA256", 600_000, byteArrayOf(1), byteArrayOf(2)),
    )

    private fun body(vararg devices: DeviceRecord) = ManifestBody(
        commitId = "c",
        writerDeviceId = "other",
        writtenAtMillis = 0,
        bucketCount = 128,
        buckets = emptyList(),
        devices = devices.toList(),
    )

    private fun device(id: String, seq: Long) = DeviceRecord(id, "model", "c$seq", seq, 0)

    @Test
    fun `same epoch at or after the base merges three-way`() {
        checkHeader(header, base, localKeyId = null) shouldBe HeaderCheck.ThreeWay
        checkHeader(header.copy(commitSeq = 11), base, localKeyId = null) shouldBe HeaderCheck.ThreeWay
        checkHeader(encrypted, base, localKeyId = "k1") shouldBe HeaderCheck.ThreeWay
    }

    @Test
    fun `a newer format is rejected before anything else`() {
        val newer = encrypted.copy(formatVersion = FORMAT_VERSION + 1, remoteEpoch = "e2", commitSeq = 0)

        checkHeader(newer, base, localKeyId = null) shouldBe
            HeaderCheck.Rejected(SyncError.FormatTooNew(FORMAT_VERSION + 1, FORMAT_VERSION))
        checkHeader(newer, null, localKeyId = null) shouldBe
            HeaderCheck.Rejected(SyncError.FormatTooNew(FORMAT_VERSION + 1))
    }

    @Test
    fun `an unknown key needs the passphrase, even for a rolled-back or new remote`() {
        val expected = HeaderCheck.Rejected(SyncError.NeedsPassphrase("k1"))

        checkHeader(encrypted, base, localKeyId = null) shouldBe expected
        checkHeader(encrypted, base, localKeyId = "k0") shouldBe expected
        checkHeader(encrypted.copy(commitSeq = 1), base, localKeyId = null) shouldBe expected
        checkHeader(encrypted, null, localKeyId = null) shouldBe expected
    }

    @Test
    fun `a plaintext remote needs no key`() {
        checkHeader(header, base, localKeyId = "k1") shouldBe HeaderCheck.ThreeWay
    }

    @Test
    fun `no base or a different epoch merges as a union`() {
        checkHeader(header, null, localKeyId = null) shouldBe HeaderCheck.Union
        checkHeader(header.copy(remoteEpoch = "e2"), base, localKeyId = null) shouldBe HeaderCheck.Union
        // A reset remote restarts its seq; that is a new epoch, not a rollback.
        checkHeader(header.copy(remoteEpoch = "e2", commitSeq = 0), base, localKeyId = null) shouldBe HeaderCheck.Union
    }

    @Test
    fun `a header seq below the base seq is a rollback`() {
        checkHeader(header.copy(commitSeq = 9), base, localKeyId = null) shouldBe
            HeaderCheck.Rejected(SyncError.RemoteRolledBack(foundSeq = 9, expectedSeq = 10))
    }

    @Test
    fun `own commit still listed passes`() {
        checkOwnCommit(body(device("me", 8)), "me", base).shouldBeNull()
        checkOwnCommit(body(device("me", 9), device("other", 10)), "me", base).shouldBeNull()
    }

    @Test
    fun `own commit missing from the lineage is a rollback`() {
        checkOwnCommit(body(device("me", 7)), "me", base) shouldBe SyncError.RemoteRolledBack(7, 8)
        checkOwnCommit(body(device("other", 10)), "me", base) shouldBe SyncError.RemoteRolledBack(null, 8)
    }

    @Test
    fun `a device that never committed has no own commit to lose`() {
        checkOwnCommit(body(), "me", base.copy(lastOwnCommitSeq = null)).shouldBeNull()
    }
}
