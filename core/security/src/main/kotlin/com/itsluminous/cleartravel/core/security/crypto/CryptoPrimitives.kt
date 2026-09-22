package com.itsluminous.cleartravel.core.security.crypto

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * The cryptographic primitives behind ADR-031, kept deliberately small and pure JVM
 * (`javax.crypto` only — no Android Keystore here, so every function is unit-tested
 * on the host). Everything else in `core:security` composes these.
 */
object CryptoPrimitives {
    const val AES_KEY_BYTES = 32
    const val GCM_IV_BYTES = 12
    const val GCM_TAG_BITS = 128
    const val SALT_BYTES = 16

    /** PBKDF2-HMAC-SHA256 iteration count for password-derived keys (OWASP 2023 floor is 210k). */
    const val DEFAULT_PBKDF2_ITERATIONS = 210_000

    private const val PBKDF2 = "PBKDF2WithHmacSHA256"
    private const val AES_GCM = "AES/GCM/NoPadding"
    private const val HMAC_SHA256 = "HmacSHA256"

    private val random = SecureRandom()

    fun randomBytes(count: Int): ByteArray = ByteArray(count).also(random::nextBytes)

    fun randomKey(): SecretKey = SecretKeySpec(randomBytes(AES_KEY_BYTES), "AES")

    /**
     * Derives [lengthBytes] of key material from [password]. The password is consumed
     * as a `CharArray` and the PBE spec cleared afterwards so callers can wipe it.
     */
    fun pbkdf2(
        password: CharArray,
        salt: ByteArray,
        iterations: Int,
        lengthBytes: Int,
    ): ByteArray {
        require(iterations > 0) { "iterations must be positive" }
        val spec = PBEKeySpec(password, salt, iterations, lengthBytes * 8)
        try {
            return SecretKeyFactory.getInstance(PBKDF2).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    /** HMAC-SHA256 keyed sub-key derivation: `HMAC(key, label)` → 32 bytes. */
    fun deriveSubKey(
        key: SecretKey,
        label: String,
    ): SecretKey {
        val mac = Mac.getInstance(HMAC_SHA256)
        mac.init(SecretKeySpec(key.encoded, HMAC_SHA256))
        return SecretKeySpec(mac.doFinal(label.encodeToByteArray()), "AES")
    }

    /** One-shot AES-256-GCM seal; the [iv] MUST be unique per key. */
    fun gcmEncrypt(
        key: SecretKey,
        iv: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray? = null,
    ): ByteArray {
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        aad?.let(cipher::updateAAD)
        return cipher.doFinal(plaintext)
    }

    /**
     * One-shot AES-256-GCM open.
     * @throws javax.crypto.AEADBadTagException on a wrong key / tampered data.
     */
    fun gcmDecrypt(
        key: SecretKey,
        iv: ByteArray,
        ciphertext: ByteArray,
        aad: ByteArray? = null,
    ): ByteArray {
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        aad?.let(cipher::updateAAD)
        return cipher.doFinal(ciphertext)
    }

    fun aesKey(bytes: ByteArray): SecretKey = SecretKeySpec(bytes, "AES")

    /** Lower-case hex, used to hand SQLCipher a raw key (`x'…'`). */
    fun toHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
