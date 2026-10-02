package com.itsluminous.cleartravel.core.security.background

import com.itsluminous.cleartravel.core.security.crypto.CryptoPrimitives
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keystore stand-in (ADR-043 tests): an ordinary AES key behind GCM ciphers. [deleteKey]
 * simulates the key being gone (null ciphers until a new key is created) and
 * [deviceLocked] a `setUnlockedDeviceRequired` refusal.
 */
class FakeBackgroundKeyWrapper : BackgroundKeyWrapper {
    private var key: SecretKey? = null
    var creations = 0
    var deletions = 0
    var deviceLocked = false

    val hasKey: Boolean get() = key != null

    override fun newEncryptCipher(): Cipher {
        creations++
        val fresh = CryptoPrimitives.randomKey()
        key = fresh
        return Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, fresh) }
    }

    override fun decryptCipher(iv: ByteArray): Cipher? {
        if (deviceLocked) return null
        val current = key ?: return null
        return Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, current, GCMParameterSpec(128, iv)) }
    }

    override fun deleteKey() {
        deletions++
        key = null
    }
}
