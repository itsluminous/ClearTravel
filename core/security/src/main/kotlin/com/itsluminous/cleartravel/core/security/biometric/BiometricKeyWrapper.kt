package com.itsluminous.cleartravel.core.security.biometric

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The Android-Keystore half of biometric unlock (ADR-031). It hands out AES-GCM
 * [Cipher]s bound to a Keystore key that REQUIRES user authentication per use, so
 * the DEK wrap/unwrap can only happen inside a successful `BiometricPrompt`
 * (`CryptoObject(cipher)`). Behind an interface because the Keystore does not exist
 * on the JVM (tests use a fake backed by an ordinary AES key).
 */
interface BiometricKeyWrapper {
    /**
     * (Re)creates the Keystore key and returns an ENCRYPT-mode cipher to authenticate;
     * after `BiometricPrompt` succeeds, pass it to `KeyVault.enableBiometric`.
     */
    fun newEncryptCipher(): Cipher

    /**
     * DECRYPT-mode cipher over the stored wrap [iv]; null when the key was
     * invalidated (new biometric enrolment) or removed — the caller then disables
     * biometric unlock and falls back to the password.
     */
    fun decryptCipher(iv: ByteArray): Cipher?

    fun deleteKey()
}

/** Production wrapper over the `AndroidKeyStore`. */
class KeystoreBiometricKeyWrapper(
    private val alias: String = DEFAULT_ALIAS,
) : BiometricKeyWrapper {
    override fun newEncryptCipher(): Cipher {
        deleteKey()
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val builder =
            KeyGenParameterSpec
                .Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(true)
                .setInvalidatedByBiometricEnrollment(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        } else {
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationValidityDurationSeconds(-1)
        }
        generator.init(builder.build())
        val key = generator.generateKey()
        return Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
    }

    override fun decryptCipher(iv: ByteArray): Cipher? {
        val key = loadKey() ?: return null
        return try {
            Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv)) }
        } catch (e: KeyPermanentlyInvalidatedException) {
            deleteKey()
            null
        }
    }

    override fun deleteKey() {
        runCatching { keyStore().deleteEntry(alias) }
    }

    private fun loadKey(): SecretKey? = runCatching { keyStore().getKey(alias, null) as? SecretKey }.getOrNull()

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    companion object {
        const val DEFAULT_ALIAS = "cleartravel.biometric.dek-wrap"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
    }
}
