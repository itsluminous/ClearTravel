package com.itsluminous.cleartravel.core.security.vault

import com.itsluminous.cleartravel.core.security.crypto.CryptoPrimitives
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKey

/**
 * [KeyVault] over a [KeyFileStore] (ADR-031). All KDF work runs on [ioDispatcher]
 * (PBKDF2 at 210k iterations is a few hundred ms on a phone). The DEK and the derived
 * sub-keys live only in this object's fields; a process death forgets them.
 */
class DefaultKeyVault(
    private val store: KeyFileStore,
    private val iterations: Int = CryptoPrimitives.DEFAULT_PBKDF2_ITERATIONS,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : KeyVault {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(initialState(store.read()))
    override val state: StateFlow<VaultState> = _state.asStateFlow()

    private var dek: SecretKey? = null
    private var ownPortableKey: PortableKey? = null
    private val adoptedKeys = mutableListOf<PortableKey>()

    override suspend fun setUp(password: CharArray) =
        mutex.withLock {
            check(store.read() == null) { "vault already set up" }
            val salt = CryptoPrimitives.randomBytes(CryptoPrimitives.SALT_BYTES)
            val newDek = CryptoPrimitives.randomKey()
            val derived = derive(password, salt, iterations)
            val file =
                VaultFile(
                    salt = salt,
                    iterations = iterations,
                    passwordWrap = wrap(derived.kek, newDek.encoded),
                    portableWrap = wrap(portableWrapKey(newDek), derived.portable.key.encoded),
                    // A fresh install has nothing to migrate.
                    filesMigrated = true,
                )
            store.write(file)
            becomeUnlocked(newDek, file)
        }

    override suspend fun unlockWithPassword(password: CharArray): Boolean =
        mutex.withLock {
            val file = store.read() ?: return false
            val derived = derive(password, file.salt, file.iterations)
            val raw = unwrap(derived.kek, file.passwordWrap) ?: return false
            becomeUnlocked(CryptoPrimitives.aesKey(raw), file)
            true
        }

    override suspend fun changePassword(
        currentPassword: CharArray,
        newPassword: CharArray,
    ): Boolean =
        mutex.withLock {
            val file = store.read() ?: return false
            val current = derive(currentPassword, file.salt, file.iterations)
            val raw = unwrap(current.kek, file.passwordWrap) ?: return false
            val salt = CryptoPrimitives.randomBytes(CryptoPrimitives.SALT_BYTES)
            val fresh = derive(newPassword, salt, iterations)
            val newDek = CryptoPrimitives.aesKey(raw)
            val updated =
                file.copy(
                    salt = salt,
                    iterations = iterations,
                    passwordWrap = wrap(fresh.kek, raw),
                    portableWrap = wrap(portableWrapKey(newDek), fresh.portable.key.encoded),
                )
            store.write(updated)
            // Re-wrap only: the DEK (and therefore every encrypted byte) is unchanged.
            // Backups written before the change stay readable through portableKeyFor's
            // adopted-key path (the old salt no longer matches → password prompt).
            becomeUnlocked(newDek, updated)
            true
        }

    override fun databaseKey(): ByteArray = CryptoPrimitives.deriveSubKey(requireDek(), KeyVault.DB_KEY_LABEL).encoded

    override fun fileKey(): SecretKey = CryptoPrimitives.deriveSubKey(requireDek(), KeyVault.FILE_KEY_LABEL)

    override fun portableKey(): PortableKey = ownPortableKey ?: throw VaultLockedException()

    override fun portableKeyFor(
        salt: ByteArray,
        iterations: Int,
    ): PortableKey? {
        ownPortableKey?.takeIf { it.matches(salt, iterations) }?.let { return it }
        return synchronized(adoptedKeys) { adoptedKeys.firstOrNull { it.matches(salt, iterations) } }
    }

    override suspend fun derivePortableKey(
        password: CharArray,
        salt: ByteArray,
        iterations: Int,
    ): PortableKey {
        val derived = derive(password, salt, iterations)
        synchronized(adoptedKeys) {
            adoptedKeys.removeAll { it.matches(salt, iterations) }
            adoptedKeys += derived.portable
        }
        return derived.portable
    }

    override suspend fun enableBiometric(authenticatedCipher: Cipher) =
        mutex.withLock {
            val file = store.read() ?: throw VaultLockedException()
            val raw = requireDek().encoded
            val ciphertext = authenticatedCipher.doFinal(raw)
            val updated = file.copy(biometricWrap = WrappedKey(iv = authenticatedCipher.iv, ciphertext = ciphertext))
            store.write(updated)
            publish(updated)
        }

    override suspend fun disableBiometric() =
        mutex.withLock {
            val file = store.read() ?: return
            val updated = file.copy(biometricWrap = null)
            store.write(updated)
            publish(updated)
        }

    override fun biometricWrapIv(): ByteArray? = store.read()?.biometricWrap?.iv

    override suspend fun unlockWithBiometric(authenticatedCipher: Cipher): Boolean =
        mutex.withLock {
            val file = store.read() ?: return false
            val wrap = file.biometricWrap ?: return false
            val raw =
                try {
                    authenticatedCipher.doFinal(wrap.ciphertext)
                } catch (e: AEADBadTagException) {
                    return false
                } catch (e: IllegalStateException) {
                    return false
                }
            // No password in hand, but the DEK unwraps the stored portable key, so
            // backups and Drive uploads work in a biometric-only session too.
            becomeUnlocked(CryptoPrimitives.aesKey(raw), file)
            true
        }

    override fun filesMigrated(): Boolean = store.read()?.filesMigrated ?: false

    override suspend fun markFilesMigrated() =
        mutex.withLock {
            val file = store.read() ?: return
            if (!file.filesMigrated) {
                val updated = file.copy(filesMigrated = true)
                store.write(updated)
            }
        }

    // ---- internals ----

    private class Derived(
        val kek: SecretKey,
        val portable: PortableKey,
    )

    private suspend fun derive(
        password: CharArray,
        salt: ByteArray,
        iterations: Int,
    ): Derived =
        withContext(ioDispatcher) {
            val material = CryptoPrimitives.pbkdf2(password, salt, iterations, DERIVED_BYTES)
            Derived(
                kek = CryptoPrimitives.aesKey(material.copyOfRange(0, CryptoPrimitives.AES_KEY_BYTES)),
                portable =
                    PortableKey(
                        salt = salt,
                        iterations = iterations,
                        key = CryptoPrimitives.aesKey(material.copyOfRange(CryptoPrimitives.AES_KEY_BYTES, DERIVED_BYTES)),
                    ),
            )
        }

    private fun wrap(
        kek: SecretKey,
        rawKey: ByteArray,
    ): WrappedKey {
        val iv = CryptoPrimitives.randomBytes(CryptoPrimitives.GCM_IV_BYTES)
        return WrappedKey(iv = iv, ciphertext = CryptoPrimitives.gcmEncrypt(kek, iv, rawKey, WRAP_AAD))
    }

    private fun unwrap(
        kek: SecretKey,
        wrapped: WrappedKey,
    ): ByteArray? =
        try {
            CryptoPrimitives.gcmDecrypt(kek, wrapped.iv, wrapped.ciphertext, WRAP_AAD)
        } catch (e: AEADBadTagException) {
            null
        }

    private fun requireDek(): SecretKey = dek ?: throw VaultLockedException()

    private fun becomeUnlocked(
        key: SecretKey,
        file: VaultFile,
    ) {
        dek = key
        ownPortableKey =
            unwrap(portableWrapKey(key), file.portableWrap)?.let { raw ->
                PortableKey(salt = file.salt, iterations = file.iterations, key = CryptoPrimitives.aesKey(raw))
            }
        publish(file)
    }

    private fun portableWrapKey(key: SecretKey): SecretKey = CryptoPrimitives.deriveSubKey(key, PORTABLE_WRAP_LABEL)

    private fun publish(file: VaultFile) {
        val biometric = file.biometricWrap != null
        _state.value = if (dek != null) VaultState.Unlocked(biometric) else VaultState.Locked(biometric)
    }

    private companion object {
        /** 32 bytes KEK + 32 bytes portable key from one PBKDF2 run. */
        const val DERIVED_BYTES = 64
        val WRAP_AAD = "cleartravel/dek-wrap/v1".encodeToByteArray()
        const val PORTABLE_WRAP_LABEL = "cleartravel/portable-wrap/v1"

        fun initialState(file: VaultFile?): VaultState =
            if (file == null) VaultState.NotSetUp else VaultState.Locked(biometricEnabled = file.biometricWrap != null)
    }
}
