package com.itsluminous.cleartravel.feature.applock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsluminous.cleartravel.core.data.repository.SettingsRepository
import com.itsluminous.cleartravel.core.data.security.SecureStorageInitializer
import com.itsluminous.cleartravel.core.security.biometric.BiometricKeyWrapper
import com.itsluminous.cleartravel.core.security.lock.AppLockController
import com.itsluminous.cleartravel.core.security.vault.KeyVault
import com.itsluminous.cleartravel.core.security.vault.VaultState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.crypto.Cipher
import javax.inject.Inject

/** What the lock gate shows (ADR-031, ADR-032). */
sealed interface AppLockUiState {
    /** First run, wizard step 1: the blocking password-creation screen. */
    data object Setup : AppLockUiState

    /** A password exists; ask for it (or offer biometrics when [biometricEnabled]). */
    data class Locked(
        val biometricEnabled: Boolean,
    ) : AppLockUiState

    /** Unlocked; the storage is being opened/migrated before the UI is revealed. */
    data object Preparing : AppLockUiState

    /**
     * ADR-032: the password exists and storage is open, but the first-run wizard
     * (Google, restore-or-start-fresh) has not finished — show its remaining steps.
     */
    data object Onboarding : AppLockUiState

    /** Everything is ready — show the app. */
    data object Ready : AppLockUiState
}

/** Feedback for the setup/unlock screens. */
data class AppLockFeedback(
    val busy: Boolean = false,
    val setupError: SetupError? = null,
    val wrongPassword: Boolean = false,
    /** Biometric unlock failed at the key level (invalidated key) — fall back to the password. */
    val biometricUnavailable: Boolean = false,
    /** Storage preparation failed; [retryPreparation] re-runs it. */
    val preparationFailed: Boolean = false,
    /**
     * ADR-032: the vault was just created with the fingerprint toggle ON — the screen
     * must run `BiometricPrompt` and report back through
     * [AppLockViewModel.completeBiometricEnrolment] / [AppLockViewModel.skipBiometricEnrolment].
     */
    val awaitingBiometricEnrolment: Boolean = false,
)

/**
 * Drives the app-lock gate: [uiState] is derived from the vault (set up? locked?),
 * the UI lock and the onboarding flag; after a successful unlock the storage is
 * prepared (database open + one-time migration) BEFORE the gate opens, so no screen
 * ever sees a locked or half-migrated store.
 */
@HiltViewModel
class AppLockViewModel
    @Inject
    constructor(
        private val keyVault: KeyVault,
        private val lockController: AppLockController,
        private val biometricKeyWrapper: BiometricKeyWrapper,
        private val storageInitializer: SecureStorageInitializer,
        private val settingsRepository: SettingsRepository,
    ) : ViewModel() {
        private val preparing = MutableStateFlow(false)
        private val _feedback = MutableStateFlow(AppLockFeedback())
        val feedback: StateFlow<AppLockFeedback> = _feedback

        val uiState: StateFlow<AppLockUiState> =
            combine(
                keyVault.state,
                lockController.locked,
                preparing,
                settingsRepository.onboardingPending,
            ) { vault, uiLocked, isPreparing, onboardingPending ->
                when {
                    vault is VaultState.NotSetUp -> AppLockUiState.Setup
                    vault is VaultState.Locked -> AppLockUiState.Locked(vault.biometricEnabled)
                    isPreparing -> AppLockUiState.Preparing
                    uiLocked -> AppLockUiState.Locked((vault as VaultState.Unlocked).biometricEnabled)
                    onboardingPending -> AppLockUiState.Onboarding
                    else -> AppLockUiState.Ready
                }
            }.stateIn(viewModelScope, SharingStarted.Eagerly, initialState())

        /**
         * Synchronous first value. The onboarding flag is not known yet; that is safe
         * because in production the vault is never already unlocked when this
         * ViewModel is created (a process start is always Locked/NotSetUp), and the
         * combined flow replaces this value as soon as the flag has been read.
         */
        private fun initialState(): AppLockUiState =
            when (val vault = keyVault.state.value) {
                VaultState.NotSetUp -> AppLockUiState.Setup
                is VaultState.Locked -> AppLockUiState.Locked(vault.biometricEnabled)
                is VaultState.Unlocked ->
                    if (lockController.locked.value) {
                        AppLockUiState.Locked(
                            vault.biometricEnabled,
                        )
                    } else {
                        AppLockUiState.Ready
                    }
            }

        /**
         * First-run setup (wizard step 1): validates the pair, marks onboarding pending
         * (ADR-032 — BEFORE the vault exists, so a death in between simply shows step 1
         * again), creates the vault, then either hands over to the biometric prompt
         * ([enableBiometric]) or prepares storage straight away.
         */
        fun setUp(
            password: String,
            confirm: String,
            enableBiometric: Boolean = false,
        ) {
            if (_feedback.value.busy) return
            val error = PasswordRules.setupError(password, confirm)
            if (error != null) {
                _feedback.update { it.copy(setupError = error) }
                return
            }
            viewModelScope.launch {
                _feedback.update { it.copy(busy = true, setupError = null) }
                try {
                    settingsRepository.setOnboardingPending(true)
                    keyVault.setUp(password.toCharArray())
                    if (enableBiometric) {
                        _feedback.update { it.copy(awaitingBiometricEnrolment = true) }
                    } else {
                        prepareAndOpen()
                    }
                } finally {
                    _feedback.update { it.copy(busy = false) }
                }
            }
        }

        /**
         * The ENCRYPT cipher to authenticate through `BiometricPrompt` while enrolling
         * from step 1; null when the Keystore refused (the screen then skips).
         */
        fun biometricEnrolCipher(): Cipher? =
            try {
                biometricKeyWrapper.newEncryptCipher()
            } catch (e: Exception) {
                null
            }

        /** Step 1 enrolment succeeded: wrap the DEK with the authenticated cipher, then continue. */
        fun completeBiometricEnrolment(authenticatedCipher: Cipher) {
            if (_feedback.value.busy) return
            viewModelScope.launch {
                _feedback.update { it.copy(busy = true, awaitingBiometricEnrolment = false) }
                try {
                    try {
                        keyVault.enableBiometric(authenticatedCipher)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Enrolment is best-effort during onboarding; Settings offers it again.
                        biometricKeyWrapper.deleteKey()
                    }
                    prepareAndOpen()
                } finally {
                    _feedback.update { it.copy(busy = false) }
                }
            }
        }

        /** Step 1 enrolment was dismissed or failed: continue without biometrics. */
        fun skipBiometricEnrolment() {
            if (_feedback.value.busy) return
            viewModelScope.launch {
                _feedback.update { it.copy(busy = true, awaitingBiometricEnrolment = false) }
                try {
                    biometricKeyWrapper.deleteKey()
                    prepareAndOpen()
                } finally {
                    _feedback.update { it.copy(busy = false) }
                }
            }
        }

        fun unlock(password: String) {
            if (_feedback.value.busy || password.isEmpty()) return
            viewModelScope.launch {
                _feedback.update { it.copy(busy = true, wrongPassword = false) }
                try {
                    // Always verifies against the stored wrap — also for a UI re-lock
                    // (lock timing) while the vault itself is still unlocked.
                    if (!keyVault.unlockWithPassword(password.toCharArray())) {
                        _feedback.update { it.copy(wrongPassword = true) }
                        return@launch
                    }
                    prepareAndOpen()
                } finally {
                    _feedback.update { it.copy(busy = false) }
                }
            }
        }

        /**
         * The DECRYPT cipher to authenticate through `BiometricPrompt`; null when
         * biometric unlock is off or the Keystore key is gone (then it is disabled and
         * the screen falls back to the password).
         */
        fun biometricUnlockCipher(): Cipher? {
            val iv = keyVault.biometricWrapIv() ?: return null
            val cipher = biometricKeyWrapper.decryptCipher(iv)
            if (cipher == null) {
                viewModelScope.launch { keyVault.disableBiometric() }
                _feedback.update { it.copy(biometricUnavailable = true) }
            }
            return cipher
        }

        /** Called with the cipher `BiometricPrompt` authenticated. */
        fun unlockWithBiometric(authenticatedCipher: Cipher) {
            if (_feedback.value.busy) return
            viewModelScope.launch {
                _feedback.update { it.copy(busy = true) }
                try {
                    val ok = keyVault.isUnlocked || keyVault.unlockWithBiometric(authenticatedCipher)
                    if (ok) prepareAndOpen() else _feedback.update { it.copy(biometricUnavailable = true) }
                } finally {
                    _feedback.update { it.copy(busy = false) }
                }
            }
        }

        fun retryPreparation() {
            if (_feedback.value.busy) return
            viewModelScope.launch {
                _feedback.update { it.copy(busy = true) }
                try {
                    prepareAndOpen()
                } finally {
                    _feedback.update { it.copy(busy = false) }
                }
            }
        }

        fun clearSetupError() {
            _feedback.update { it.copy(setupError = null, wrongPassword = false) }
        }

        private suspend fun prepareAndOpen() {
            preparing.value = true
            try {
                storageInitializer.prepare()
                _feedback.update { it.copy(preparationFailed = false) }
                lockController.unlock()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _feedback.update { it.copy(preparationFailed = true) }
            } finally {
                preparing.value = false
            }
        }
    }
