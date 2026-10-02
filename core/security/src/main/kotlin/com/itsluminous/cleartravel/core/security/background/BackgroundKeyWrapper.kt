package com.itsluminous.cleartravel.core.security.background

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * ADR-043: the Android-Keystore half of the OPT-IN "allow sync while locked" key. Unlike
 * [com.itsluminous.cleartravel.core.security.biometric.BiometricKeyWrapper] the key
 * needs NO user authentication — that is the whole point (a background worker has no
 * user to authenticate) and the whole risk (anything running inside this app on an
 * unlocked device can unwrap the DEK without the password). Mitigations baked into the
 * key: `setUnlockedDeviceRequired(true)` (API 28+: unusable while the device itself is
 * screen-locked, so a stolen locked phone cannot run the unwrap) and StrongBox when the
 * hardware offers it (falls back to the TEE-backed Keystore otherwise).
 *
 * Behind an interface because the Keystore does not exist on the JVM — the vault tests
 * and the hermetic e2e suite use a fake backed by an ordinary AES key.
 */
interface BackgroundKeyWrapper {
    /** (Re)creates the Keystore key and returns an ENCRYPT-mode cipher to wrap the DEK with. */
    fun newEncryptCipher(): Cipher

    /**
     * DECRYPT-mode cipher over the stored wrap [iv]; null when the key is gone or
     * unusable right now (device locked, key invalidated) — the caller treats the vault
     * as locked and the worker defers.
     */
    fun decryptCipher(iv: ByteArray): Cipher?

    fun deleteKey()
}

/** Production wrapper over the `AndroidKeyStore`. */
class KeystoreBackgroundKeyWrapper(
    private val alias: String = DEFAULT_ALIAS,
) : BackgroundKeyWrapper {
    override fun newEncryptCipher(): Cipher {
        deleteKey()
        val key = generateKey(strongBox = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
        return Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
    }

    override fun decryptCipher(iv: ByteArray): Cipher? {
        val key = loadKey() ?: return null
        return try {
            Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv)) }
        } catch (e: KeyPermanentlyInvalidatedException) {
            deleteKey()
            null
        } catch (e: GeneralSecurityException) {
            // Device locked (setUnlockedDeviceRequired) or a transient Keystore fault: not now.
            null
        } catch (e: ProviderException) {
            null
        }
    }

    override fun deleteKey() {
        runCatching { keyStore().deleteEntry(alias) }
    }

    /** StrongBox first when asked; a device without it throws and we retry in the TEE. */
    private fun generateKey(strongBox: Boolean): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val builder =
            KeyGenParameterSpec
                .Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.setUnlockedDeviceRequired(true)
            if (strongBox) builder.setIsStrongBoxBacked(true)
        }
        generator.init(builder.build())
        return try {
            generator.generateKey()
        } catch (e: ProviderException) {
            // `StrongBoxUnavailableException` (API 28) is a ProviderException; catching the
            // parent keeps this class loadable on API 26–27 and also covers devices that
            // report a missing StrongBox as a generic provider failure.
            if (!strongBox) throw e
            generateKey(strongBox = false)
        }
    }

    private fun loadKey(): SecretKey? = runCatching { keyStore().getKey(alias, null) as? SecretKey }.getOrNull()

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    companion object {
        const val DEFAULT_ALIAS = "cleartravel.background.dek-wrap"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
    }
}

/**
 * The default for a vault built without the opt-in wiring (unit tests of other
 * modules): no background key can ever exist.
 */
object NoBackgroundKeyWrapper : BackgroundKeyWrapper {
    override fun newEncryptCipher(): Cipher = throw UnsupportedOperationException("background unlock is not wired")

    override fun decryptCipher(iv: ByteArray): Cipher? = null

    override fun deleteKey() = Unit
}
