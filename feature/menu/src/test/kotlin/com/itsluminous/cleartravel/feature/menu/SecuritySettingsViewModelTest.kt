package com.itsluminous.cleartravel.feature.menu

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.data.sync.SyncWorkKind
import com.itsluminous.cleartravel.core.security.background.BackgroundKeyWrapper
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

    /** ADR-043 Keystore stand-in; [failCreation] makes the Keystore refuse. */
    private class FakeBackgroundKeyWrapper : BackgroundKeyWrapper {
        private var key = CryptoPrimitives.randomKey()
        var present = false
        var deletions = 0
        var failCreation = false

        override fun newEncryptCipher(): Cipher {
            if (failCreation) throw java.security.ProviderException("keystore says no")
            key = CryptoPrimitives.randomKey()
            present = true
            return Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        }

        override fun decryptCipher(iv: ByteArray): Cipher? =
            if (present) {
                Cipher
                    .getInstance(
                        "AES/GCM/NoPadding",
                    ).apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv)) }
            } else {
                null
            }

        override fun deleteKey() {
            deletions++
            present = false
        }
    }

    private val store = InMemoryKeyFileStore()
    private lateinit var vault: DefaultKeyVault
    private val biometric = FakeBiometricKeyWrapper()
    private val background = FakeBackgroundKeyWrapper()
    private val settings = FakeSettingsRepository()
    private val syncState = FakeBackgroundSyncStateStore()

    @Before
    fun setUp() =
        runTest {
            vault = DefaultKeyVault(store, iterations = 1_000, ioDispatcher = UnconfinedTestDispatcher(), backgroundKeyWrapper = background)
            vault.setUp("original-1".toCharArray())
        }

    private fun viewModel() = SecuritySettingsViewModel(vault, biometric, settings, syncState)

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
            assertThat(viewModel.uiState.value.lockTiming).isEqualTo(LockTiming.ONE_MINUTE) // ADR-034 default
            viewModel.setLockTiming(LockTiming.FIVE_MINUTES)
            assertThat(viewModel.uiState.value.lockTiming).isEqualTo(LockTiming.FIVE_MINUTES)
        }

    @Test
    fun backgroundSync_switchOn_asksForPassword_wrongKeepsDialog_rightReWraps_offDeletesKey() =
        runTest {
            val viewModel = viewModel()
            assertThat(viewModel.uiState.value.backgroundSyncEnabled).isFalse()
            assertThat(viewModel.uiState.value.confirmingBackgroundSync).isFalse()

            viewModel.requestEnableBackgroundSync()
            assertThat(viewModel.uiState.value.confirmingBackgroundSync).isTrue()
            assertThat(store.current!!.backgroundWrap).isNull() // nothing happens before the password

            viewModel.events.test {
                viewModel.enableBackgroundSync("not-it")
                assertThat(awaitItem()).isEqualTo(SecurityEvent.BackgroundSyncPasswordWrong)
                assertThat(viewModel.uiState.value.confirmingBackgroundSync).isTrue()
                assertThat(viewModel.uiState.value.backgroundSyncEnabled).isFalse()
                assertThat(store.current!!.backgroundWrap).isNull()

                viewModel.enableBackgroundSync("original-1")
                assertThat(awaitItem()).isEqualTo(SecurityEvent.BackgroundSyncEnabled)
                assertThat(viewModel.uiState.value.confirmingBackgroundSync).isFalse()
                assertThat(viewModel.uiState.value.backgroundSyncEnabled).isTrue()
                assertThat(store.current!!.backgroundWrap).isNotNull()
                assertThat(background.present).isTrue()

                // A cold vault over the same file self-unlocks — the worker path.
                val cold =
                    DefaultKeyVault(store, iterations = 1_000, ioDispatcher = UnconfinedTestDispatcher(), backgroundKeyWrapper = background)
                assertThat(cold.unlockWithBackgroundKey()).isTrue()

                viewModel.disableBackgroundSync()
                assertThat(awaitItem()).isEqualTo(SecurityEvent.BackgroundSyncDisabled)
                assertThat(viewModel.uiState.value.backgroundSyncEnabled).isFalse()
                assertThat(store.current!!.backgroundWrap).isNull()
                assertThat(background.present).isFalse()
                assertThat(background.deletions).isAtLeast(1)
            }
        }

    @Test
    fun backgroundSync_keystoreRefusal_reportsFailure_andLeavesTheSwitchOff() =
        runTest {
            val viewModel = viewModel()
            background.failCreation = true
            viewModel.requestEnableBackgroundSync()
            viewModel.events.test {
                viewModel.enableBackgroundSync("original-1")
                assertThat(awaitItem()).isEqualTo(SecurityEvent.BackgroundSyncSetupFailed)
            }
            assertThat(viewModel.uiState.value.confirmingBackgroundSync).isFalse()
            assertThat(viewModel.uiState.value.backgroundSyncEnabled).isFalse()
            assertThat(store.current!!.backgroundWrap).isNull()
        }

    @Test
    fun syncStatuses_mirrorTheStore() =
        runTest {
            val viewModel = viewModel()
            assertThat(
                viewModel.uiState.value.syncStatuses
                    .map { it.kind },
            ).containsExactlyElementsIn(SyncWorkKind.entries).inOrder()
            val at = java.time.Instant.parse("2026-10-02T09:00:00Z")
            syncState.recordDeferred(SyncWorkKind.FLIGHT_POLL, at)
            syncState.recordCompleted(SyncWorkKind.SCHEDULED_BACKUP, at)
            val byKind =
                viewModel.uiState.value.syncStatuses
                    .associateBy { it.kind }
            assertThat(byKind[SyncWorkKind.FLIGHT_POLL]!!.lastDeferredAt).isEqualTo(at)
            assertThat(byKind[SyncWorkKind.FLIGHT_POLL]!!.deferredSinceUnlock).isEqualTo(1)
            assertThat(byKind[SyncWorkKind.SCHEDULED_BACKUP]!!.lastCompletedAt).isEqualTo(at)
        }
}
