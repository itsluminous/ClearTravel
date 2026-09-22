package com.itsluminous.cleartravel.feature.menu

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.security.biometric.BiometricKeyWrapper
import com.itsluminous.cleartravel.core.security.crypto.CryptoPrimitives
import com.itsluminous.cleartravel.core.security.lock.LockTiming
import com.itsluminous.cleartravel.core.security.vault.DefaultKeyVault
import com.itsluminous.cleartravel.core.security.vault.InMemoryKeyFileStore
import com.itsluminous.cleartravel.core.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

@OptIn(ExperimentalCoroutinesApi::class)
class SecuritySettingsViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class FakeBiometricKeyWrapper : BiometricKeyWrapper {
        private val key = CryptoPrimitives.randomKey()
        var deletions = 0

        override fun newEncryptCipher(): Cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }

        override fun decryptCipher(iv: ByteArray): Cipher =
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv)) }

        override fun deleteKey() {
            deletions++
        }
    }

    private val store = InMemoryKeyFileStore()
    private lateinit var vault: DefaultKeyVault
    private val biometric = FakeBiometricKeyWrapper()
    private val settings = FakeSettingsRepository()

    @Before
    fun setUp() =
        runTest {
            vault = DefaultKeyVault(store, iterations = 1_000, ioDispatcher = UnconfinedTestDispatcher())
            vault.setUp("original-1".toCharArray())
        }

    private fun viewModel() = SecuritySettingsViewModel(vault, biometric, settings)

    @Test
    fun changePassword_validatesLocally_rejectsWrongCurrent_thenReWraps() =
        runTest {
            val viewModel = viewModel()
            val fileKey = vault.fileKey().encoded
            viewModel.openChangePassword()

            viewModel.changePassword("original-1", "short", "short")
            assertThat(viewModel.uiState.value.changeError).isEqualTo(ChangePasswordError.TOO_SHORT)
            viewModel.changePassword("original-1", "new-password", "new-passwor")
            assertThat(viewModel.uiState.value.changeError).isEqualTo(ChangePasswordError.MISMATCH)

            viewModel.events.test {
                viewModel.changePassword("wrong", "new-password", "new-password")
                assertThat(awaitItem()).isEqualTo(SecurityEvent.CurrentPasswordWrong)
                assertThat(viewModel.uiState.value.changingPassword).isTrue()

                viewModel.changePassword("original-1", "new-password", "new-password")
                assertThat(awaitItem()).isEqualTo(SecurityEvent.PasswordChanged)
            }
            assertThat(viewModel.uiState.value.changingPassword).isFalse()
            // Same DEK: nothing needs re-encrypting.
            assertThat(vault.fileKey().encoded).isEqualTo(fileKey)
            val reopened = DefaultKeyVault(store, iterations = 1_000, ioDispatcher = UnconfinedTestDispatcher())
            assertThat(reopened.unlockWithPassword("new-password".toCharArray())).isTrue()
        }

    @Test
    fun biometric_enableThenDisable_updatesVaultAndCleansKeystore() =
        runTest {
            val viewModel = viewModel()
            assertThat(viewModel.uiState.value.hasPassword).isTrue()
            assertThat(viewModel.uiState.value.biometricEnabled).isFalse()

            viewModel.events.test {
                val cipher = viewModel.biometricEnrolCipher()!!
                viewModel.enableBiometric(cipher) // what BiometricPrompt would hand back
                assertThat(awaitItem()).isEqualTo(SecurityEvent.BiometricEnabled)
                assertThat(viewModel.uiState.value.biometricEnabled).isTrue()
                assertThat(store.current!!.biometricWrap).isNotNull()

                viewModel.disableBiometric()
                assertThat(awaitItem()).isEqualTo(SecurityEvent.BiometricDisabled)
                assertThat(viewModel.uiState.value.biometricEnabled).isFalse()
                assertThat(store.current!!.biometricWrap).isNull()
                assertThat(biometric.deletions).isEqualTo(1)
            }
        }

    @Test
    fun biometricPromptFailure_leavesBiometricsOff_andDropsTheKey() =
        runTest {
            val viewModel = viewModel()
            viewModel.biometricEnrolCipher()
            viewModel.events.test {
                viewModel.biometricPromptFailed()
                assertThat(awaitItem()).isEqualTo(SecurityEvent.BiometricSetupFailed)
            }
            assertThat(viewModel.uiState.value.biometricEnabled).isFalse()
            assertThat(biometric.deletions).isEqualTo(1)
        }

    @Test
    fun lockTiming_isPersistedThroughSettings() =
        runTest {
            val viewModel = viewModel()
            assertThat(viewModel.uiState.value.lockTiming).isEqualTo(LockTiming.NEVER)
            viewModel.setLockTiming(LockTiming.FIVE_MINUTES)
            assertThat(viewModel.uiState.value.lockTiming).isEqualTo(LockTiming.FIVE_MINUTES)
        }
}
