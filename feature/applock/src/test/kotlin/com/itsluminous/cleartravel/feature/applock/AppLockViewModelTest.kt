package com.itsluminous.cleartravel.feature.applock

import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.data.security.SecureStorageInitializer
import com.itsluminous.cleartravel.core.data.security.StorageMigrationReport
import com.itsluminous.cleartravel.core.security.biometric.BiometricKeyWrapper
import com.itsluminous.cleartravel.core.security.crypto.CryptoPrimitives
import com.itsluminous.cleartravel.core.security.lock.AppLockController
import com.itsluminous.cleartravel.core.security.vault.DefaultKeyVault
import com.itsluminous.cleartravel.core.security.vault.InMemoryKeyFileStore
import com.itsluminous.cleartravel.core.security.vault.VaultState
import com.itsluminous.cleartravel.core.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

@OptIn(ExperimentalCoroutinesApi::class)
class AppLockViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val store = InMemoryKeyFileStore()
    private val lockController = AppLockController()

    /** Keystore stand-in: a plain AES key behind GCM ciphers; [invalidated] mimics a re-enrolment. */
    private class FakeBiometricKeyWrapper : BiometricKeyWrapper {
        private var key = CryptoPrimitives.randomKey()
        var invalidated = false

        override fun newEncryptCipher(): Cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }

        override fun decryptCipher(iv: ByteArray): Cipher? =
            if (invalidated) {
                null
            } else {
                Cipher
                    .getInstance(
                        "AES/GCM/NoPadding",
                    ).apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv)) }
            }

        override fun deleteKey() = Unit
    }

    private class FakeInitializer : SecureStorageInitializer {
        var calls = 0
        var failNext = false

        override suspend fun prepare(): StorageMigrationReport {
            calls++
            if (failNext) {
                failNext = false
                throw IOException("disk full")
            }
            return StorageMigrationReport.NOTHING
        }
    }

    private val biometric = FakeBiometricKeyWrapper()
    private val initializer = FakeInitializer()

    private fun vault() = DefaultKeyVault(store, iterations = 1_000, ioDispatcher = UnconfinedTestDispatcher())

    private fun viewModel(vault: DefaultKeyVault = vault()) = AppLockViewModel(vault, lockController, biometric, initializer)

    @Test
    fun freshInstall_showsSetup_validatesPair_thenPreparesAndOpens() =
        runTest {
            val viewModel = viewModel()
            assertThat(viewModel.uiState.value).isEqualTo(AppLockUiState.Setup)

            viewModel.setUp("short", "short")
            assertThat(viewModel.feedback.value.setupError).isEqualTo(SetupError.TOO_SHORT)
            viewModel.setUp("long-enough-1", "long-enough-2")
            assertThat(viewModel.feedback.value.setupError).isEqualTo(SetupError.MISMATCH)
            assertThat(store.current).isNull()

            viewModel.setUp("long-enough-1", "long-enough-1")

            assertThat(store.current).isNotNull()
            assertThat(initializer.calls).isEqualTo(1)
            assertThat(viewModel.uiState.value).isEqualTo(AppLockUiState.Ready)
            assertThat(lockController.locked.value).isFalse()
        }

    @Test
    fun coldStart_isLocked_wrongPasswordStays_rightPasswordOpens() =
        runTest {
            viewModel().setUp("pw-pw-pw-1", "pw-pw-pw-1")
            lockController.lock() // simulate a new process: UI locked, vault forgets the DEK
            val viewModel = viewModel(vault()) // new vault over the same key file → Locked
            assertThat(viewModel.uiState.value).isEqualTo(AppLockUiState.Locked(biometricEnabled = false))

            viewModel.unlock("nope")
            assertThat(viewModel.feedback.value.wrongPassword).isTrue()
            assertThat(viewModel.uiState.value).isEqualTo(AppLockUiState.Locked(biometricEnabled = false))

            viewModel.unlock("pw-pw-pw-1")
            assertThat(viewModel.uiState.value).isEqualTo(AppLockUiState.Ready)
        }

    @Test
    fun preparationFailure_keepsTheGateClosed_untilRetrySucceeds() =
        runTest {
            initializer.failNext = true
            val viewModel = viewModel()

            viewModel.setUp("pw-pw-pw-1", "pw-pw-pw-1")

            assertThat(viewModel.feedback.value.preparationFailed).isTrue()
            assertThat(viewModel.uiState.value).isEqualTo(AppLockUiState.Locked(biometricEnabled = false))
            viewModel.retryPreparation()
            assertThat(viewModel.feedback.value.preparationFailed).isFalse()
            assertThat(viewModel.uiState.value).isEqualTo(AppLockUiState.Ready)
        }

    @Test
    fun biometric_unlock_roundTrip_andInvalidatedKeyFallsBackToPassword() =
        runTest {
            val setupVault = vault()
            viewModel(setupVault).setUp("pw-pw-pw-1", "pw-pw-pw-1")
            setupVault.enableBiometric(biometric.newEncryptCipher())
            lockController.lock()

            val viewModel = viewModel(vault())
            assertThat(viewModel.uiState.value).isEqualTo(AppLockUiState.Locked(biometricEnabled = true))
            val cipher = viewModel.biometricUnlockCipher()
            assertThat(cipher).isNotNull()
            viewModel.unlockWithBiometric(cipher!!)
            assertThat(viewModel.uiState.value).isEqualTo(AppLockUiState.Ready)

            // Biometrics re-enrolled: the Keystore key is gone → biometric unlock is disabled.
            lockController.lock()
            biometric.invalidated = true
            val relocked = viewModel(vault())
            assertThat(relocked.biometricUnlockCipher()).isNull()
            assertThat(relocked.feedback.value.biometricUnavailable).isTrue()
            assertThat((relocked.uiState.value as AppLockUiState.Locked).biometricEnabled).isFalse()
            assertThat(store.current!!.biometricWrap).isNull()
        }

    @Test
    fun uiRelock_withVaultStillUnlocked_requiresThePasswordAgain() =
        runTest {
            val vault = vault()
            val viewModel = viewModel(vault)
            viewModel.setUp("pw-pw-pw-1", "pw-pw-pw-1")
            assertThat(vault.state.value).isEqualTo(VaultState.Unlocked(biometricEnabled = false))

            lockController.lock() // lock timing elapsed; DEK still cached for workers
            assertThat(viewModel.uiState.value).isEqualTo(AppLockUiState.Locked(biometricEnabled = false))
            assertThat(vault.isUnlocked).isTrue()

            viewModel.unlock("wrong")
            assertThat(viewModel.uiState.value).isEqualTo(AppLockUiState.Locked(biometricEnabled = false))
            viewModel.unlock("pw-pw-pw-1")
            assertThat(viewModel.uiState.value).isEqualTo(AppLockUiState.Ready)
        }
}
