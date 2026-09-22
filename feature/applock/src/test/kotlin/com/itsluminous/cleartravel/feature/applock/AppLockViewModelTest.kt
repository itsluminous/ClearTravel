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
import kotlinx.coroutines.launch
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
    private val settings = FakeSettingsRepository()

    private fun vault() = DefaultKeyVault(store, iterations = 1_000, ioDispatcher = UnconfinedTestDispatcher())

    private fun viewModel(vault: DefaultKeyVault = vault()) = AppLockViewModel(vault, lockController, biometric, initializer, settings)

    /** The wizard's steps 2–4 finished (ADR-032) — the flag is what the gate keys on. */
    private fun wizardDone() {
        settings.onboarding.value = false
    }

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
            // ADR-032: the password is step 1 of the wizard — the gate shows steps 2–4 next.
            assertThat(viewModel.uiState.value).isEqualTo(AppLockUiState.Onboarding)
            assertThat(settings.onboardingWrites).containsExactly(true)
            assertThat(lockController.locked.value).isFalse()

            settings.setOnboardingPending(false) // the wizard finished
            assertThat(viewModel.uiState.value).isEqualTo(AppLockUiState.Ready)
        }

    @Test
    fun setUp_withFingerprintToggle_createsVaultFirst_thenWrapsInsideThePrompt() =
        runTest {
            val vault = vault()
            val viewModel = viewModel(vault)

            viewModel.setUp("long-enough-1", "long-enough-1", enableBiometric = true)

            // Vault exists, storage NOT prepared yet: the screen must run BiometricPrompt.
            assertThat(store.current).isNotNull()
            assertThat(viewModel.feedback.value.awaitingBiometricEnrolment).isTrue()
            assertThat(initializer.calls).isEqualTo(0)
            // ADR-034: the gate must KEEP step 1 on screen — it hosts the prompt. (The
            // pre-fix state here was Locked(false): the UI lock is still engaged, so the
            // setup screen was disposed and the prompt never ran.)
            assertThat(viewModel.uiState.value).isEqualTo(AppLockUiState.Setup)
            assertThat(lockController.locked.value).isTrue()

            viewModel.completeBiometricEnrolment(viewModel.biometricEnrolCipher()!!)

            assertThat(viewModel.feedback.value.awaitingBiometricEnrolment).isFalse()
            assertThat(vault.state.value).isEqualTo(VaultState.Unlocked(biometricEnabled = true))
            assertThat(store.current!!.biometricWrap).isNotNull()
            assertThat(initializer.calls).isEqualTo(1)
            assertThat(viewModel.uiState.value).isEqualTo(AppLockUiState.Onboarding)
        }

    @Test
    fun setUp_withFingerprintToggle_promptDismissed_continuesWithoutBiometrics() =
        runTest {
            val vault = vault()
            val viewModel = viewModel(vault)
            viewModel.setUp("long-enough-1", "long-enough-1", enableBiometric = true)
            assertThat(viewModel.feedback.value.awaitingBiometricEnrolment).isTrue()
            assertThat(viewModel.uiState.value).isEqualTo(AppLockUiState.Setup)

            viewModel.skipBiometricEnrolment()

            assertThat(vault.state.value).isEqualTo(VaultState.Unlocked(biometricEnabled = false))
            assertThat(store.current!!.biometricWrap).isNull()
            assertThat(initializer.calls).isEqualTo(1)
            assertThat(viewModel.uiState.value).isEqualTo(AppLockUiState.Onboarding)
        }

    @Test
    fun setUp_withFingerprintToggle_neverShowsTheUnlockScreen_untilStorageIsOpen() =
        runTest {
            // ADR-034 regression: every state the gate emits between "Create password"
            // and the wizard must be Setup or Preparing — a Locked emission disposes the
            // setup screen (and its BiometricPrompt) and asks for the password again.
            val vault = vault()
            val viewModel = viewModel(vault)
            val seen = mutableListOf<AppLockUiState>()
            val job =
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    viewModel.uiState.collect { seen += it }
                }

            viewModel.setUp("long-enough-1", "long-enough-1", enableBiometric = true)
            viewModel.completeBiometricEnrolment(viewModel.biometricEnrolCipher()!!)

            assertThat(seen.filterIsInstance<AppLockUiState.Locked>()).isEmpty()
            assertThat(seen.last()).isEqualTo(AppLockUiState.Onboarding)
            assertThat(vault.state.value).isEqualTo(VaultState.Unlocked(biometricEnabled = true))
            job.cancel()

            // The enrolled wrap survives a cold start: the unlock screen offers biometrics.
            lockController.lock()
            val relaunched = viewModel(vault())
            assertThat(relaunched.uiState.value).isEqualTo(AppLockUiState.Locked(biometricEnabled = true))
        }

    @Test
    fun setUp_withFingerprintToggle_enrolmentFailure_continuesWithoutBiometrics_noLockedGap() =
        runTest {
            val vault = vault()
            val viewModel = viewModel(vault)
            val seen = mutableListOf<AppLockUiState>()
            val job =
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    viewModel.uiState.collect { seen += it }
                }

            viewModel.setUp("long-enough-1", "long-enough-1", enableBiometric = true)
            // An uninitialised cipher makes the wrap throw — the Keystore failure path.
            viewModel.completeBiometricEnrolment(Cipher.getInstance("AES/GCM/NoPadding"))

            assertThat(seen.filterIsInstance<AppLockUiState.Locked>()).isEmpty()
            assertThat(viewModel.uiState.value).isEqualTo(AppLockUiState.Onboarding)
            assertThat(vault.state.value).isEqualTo(VaultState.Unlocked(biometricEnabled = false))
            assertThat(initializer.calls).isEqualTo(1)
            job.cancel()
        }

    @Test
    fun resumeAfterDeath_midWizard_unlocksThenResumesOnboarding_neverSetupAgain() =
        runTest {
            viewModel().setUp("pw-pw-pw-1", "pw-pw-pw-1")
            assertThat(settings.onboarding.value).isTrue()
            lockController.lock() // process died before the wizard finished

            val viewModel = viewModel(vault())
            assertThat(viewModel.uiState.value).isEqualTo(AppLockUiState.Locked(biometricEnabled = false))
            viewModel.unlock("pw-pw-pw-1")
            assertThat(viewModel.uiState.value).isEqualTo(AppLockUiState.Onboarding)
        }

    @Test
    fun upgradedInstall_withoutTheFlag_neverSeesTheWizard() =
        runTest {
            // Pre-ADR-032 install: vault exists, the onboarding flag was never written.
            viewModel().setUp("pw-pw-pw-1", "pw-pw-pw-1")
            settings.onboarding.value = false
            settings.onboardingWrites.clear()
            lockController.lock()

            val viewModel = viewModel(vault())
            viewModel.unlock("pw-pw-pw-1")
            assertThat(viewModel.uiState.value).isEqualTo(AppLockUiState.Ready)
            assertThat(settings.onboardingWrites).isEmpty()
        }

    @Test
    fun coldStart_isLocked_wrongPasswordStays_rightPasswordOpens() =
        runTest {
            viewModel().setUp("pw-pw-pw-1", "pw-pw-pw-1")
            wizardDone()
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
            assertThat(viewModel.uiState.value).isEqualTo(AppLockUiState.Onboarding)
            wizardDone()
            assertThat(viewModel.uiState.value).isEqualTo(AppLockUiState.Ready)
        }

    @Test
    fun biometric_unlock_roundTrip_andInvalidatedKeyFallsBackToPassword() =
        runTest {
            val setupVault = vault()
            viewModel(setupVault).setUp("pw-pw-pw-1", "pw-pw-pw-1")
            wizardDone()
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
            wizardDone()
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
