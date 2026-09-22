package com.itsluminous.cleartravel.di

import com.itsluminous.cleartravel.core.security.biometric.BiometricKeyWrapper
import com.itsluminous.cleartravel.core.security.crypto.CryptoPrimitives
import com.itsluminous.cleartravel.core.security.di.SecurityModule
import com.itsluminous.cleartravel.core.security.file.LocalFileCipher
import com.itsluminous.cleartravel.core.security.lock.AppLockController
import com.itsluminous.cleartravel.core.security.vault.DefaultKeyVault
import com.itsluminous.cleartravel.core.security.vault.InMemoryKeyFileStore
import com.itsluminous.cleartravel.core.security.vault.KeyVault
import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import kotlinx.coroutines.runBlocking
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Singleton

/**
 * Hermetic security for the e2e suite (ADR-031): an in-memory vault that is already
 * SET UP and UNLOCKED, plus an open UI lock, so [com.itsluminous.cleartravel.MainActivity]
 * renders the app straight away and every pre-existing e2e runs unchanged. Files the
 * app writes during a test are encrypted under this process-only key (plaintext files
 * seeded by tests still read — the cipher passes them through). No Keystore: the
 * biometric wrapper is a plain AES stand-in. `AppLockSetupE2eTest` uninstalls this
 * module to exercise the real first-run flow.
 */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [SecurityModule::class])
object TestSecurityModule {
    const val TEST_PASSWORD = "e2e-test-password"

    @Provides
    @Singleton
    fun provideKeyVault(): KeyVault =
        DefaultKeyVault(InMemoryKeyFileStore(), iterations = 1_000).also { vault ->
            runBlocking { vault.setUp(TEST_PASSWORD.toCharArray()) }
        }

    @Provides
    @Singleton
    fun provideLocalFileCipher(vault: KeyVault): LocalFileCipher = LocalFileCipher(key = { vault.fileKey() })

    @Provides
    @Singleton
    fun provideBiometricKeyWrapper(): BiometricKeyWrapper = FakeBiometricKeyWrapper()

    @Provides
    @Singleton
    fun provideAppLockController(): AppLockController = AppLockController().also { it.unlock() }
}

/** Keystore stand-in for tests: an ordinary AES key behind GCM ciphers. */
class FakeBiometricKeyWrapper : BiometricKeyWrapper {
    private val key = CryptoPrimitives.randomKey()

    override fun newEncryptCipher(): Cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }

    override fun decryptCipher(iv: ByteArray): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv)) }

    override fun deleteKey() = Unit
}
