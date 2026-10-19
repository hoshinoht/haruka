package haruka.sync.work

import haruka.sync.crypto.PayloadSlot
import haruka.sync.crypto.SyncCryptoException
import haruka.sync.crypto.SyncKey
import haruka.sync.remote.RemoteLayout
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The blob [SyncKeyVault] wraps must give back a key that reads what the original wrote, and nothing else. */
class SyncKeyExportTest {

    private val key = SyncKey.create("correct horse battery staple")
    private val layout = RemoteLayout("app.haruka.dev")

    @Test
    fun `an imported key opens what the exported one sealed`() {
        val slot = PayloadSlot.Categories
        val stored = key.encryptPayload(layout, slot, byteArrayOf(1, 2, 3))

        val imported = SyncKey.import(key.export())

        imported.keyId shouldBe key.keyId
        imported.params shouldBe key.params
        imported.decryptPayload(layout, slot, stored).toList() shouldBe listOf<Byte>(1, 2, 3)
    }

    @Test
    fun `an altered blob is refused`() {
        val exported = key.export()
        exported[exported.size - 1] = (exported[exported.size - 1] + 1).toByte()

        assertThrows<SyncCryptoException> { SyncKey.import(exported) }
    }

    @Test
    fun `garbage is refused`() {
        assertThrows<SyncCryptoException> { SyncKey.import(byteArrayOf(0x7f, 0x01, 0x02)) }
    }
}
