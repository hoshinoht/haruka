package haruka.sync.work

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import haruka.sync.crypto.SyncCryptoException
import haruka.sync.crypto.SyncKey
import haruka.sync.local.SYNC_LOG_TAG
import logcat.LogPriority
import logcat.logcat
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keeps the sync encryption key between syncs, wrapped by a non-exportable AES-GCM key in the Android Keystore.
 *
 * The wrapped blob is an app-state preference, so Mihon backups never carry it; it would be useless elsewhere anyway
 * because the wrapping key never leaves this device's Keystore. Losing it (app data cleared, Keystore reset) only
 * means asking for the passphrase again.
 */
class SyncKeyVault(preferenceStore: PreferenceStore) {

    private val wrapped: Preference<String> = preferenceStore.getString(KEY, "")

    val hasKey: Boolean get() = wrapped.get().isNotEmpty()

    fun store(key: SyncKey) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // The Keystore picks the IV itself (randomized encryption is required by default).
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey())
        val exported = key.export()
        try {
            val sealed = cipher.doFinal(exported)
            wrapped.set(encode(cipher.iv) + SEPARATOR + encode(sealed))
        } finally {
            exported.fill(0)
        }
    }

    /** The stored key, or null when there is none or it can no longer be unwrapped (then it is dropped). */
    fun load(): SyncKey? {
        val value = wrapped.get().ifEmpty { return null }
        val parts = value.split(SEPARATOR)
        if (parts.size != 2) return null.also { drop("malformed") }
        return try {
            val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            val wrapping = keyStore.getKey(ALIAS, null) as? SecretKey ?: return null.also { drop("no wrapping key") }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, wrapping, GCMParameterSpec(TAG_BITS, decode(parts[0])))
            val exported = cipher.doFinal(decode(parts[1]))
            try {
                SyncKey.import(exported)
            } finally {
                exported.fill(0)
            }
        } catch (e: GeneralSecurityException) {
            null.also { drop(e.javaClass.simpleName) }
        } catch (e: SyncCryptoException) {
            null.also { drop(e.javaClass.simpleName) }
        } catch (e: IllegalArgumentException) {
            // Base64 that does not decode.
            null.also { drop(e.javaClass.simpleName) }
        }
    }

    private fun drop(reason: String) {
        logcat(LogPriority.WARN, SYNC_LOG_TAG) { "stored key unusable ($reason); dropped" }
        clear()
    }

    fun clear() {
        wrapped.delete()
    }

    private fun wrappingKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_BITS)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            .apply { init(spec) }
            .generateKey()
    }

    private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun decode(text: String) = Base64.decode(text, Base64.NO_WRAP)

    private companion object {
        val KEY = Preference.appStateKey("haruka_sync_wrapped_key")
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "haruka_sync_key_wrap"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val KEY_BITS = 256
        const val SEPARATOR = ":"
    }
}
