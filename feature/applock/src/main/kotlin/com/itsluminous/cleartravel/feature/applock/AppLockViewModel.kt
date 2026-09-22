package com.itsluminous.cleartravel.feature.applock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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

/** What the lock gate shows (ADR-031). */
sealed interface AppLockUiState {
    /** First run: the blocking password-creation screen. */
    data object Setup : AppLockUiState

    /** A password exists; ask for it (or offer biometrics when [biometricEnabled]). */
    data class Locked(
        val biometricEnabled: Boolean,
    ) : AppLockUiState

    /** Unlocked; the storage is being opened/migrated before the UI is revealed. */
    data object Preparing : AppLockUiState

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
)

/**
 * Drives the app-lock gate: [uiState] is derived from the vault (set up? locked?)
 * and the UI lock; after a successful unlock the storage is prepared (database open
 * + one-time migration) BEFORE the gate opens, so no screen ever sees a locked or
 * half-migrated store.
 */
@HiltViewModel
class AppLockViewModel
    @Inject
    constructor(
        private val keyVault: KeyVault,
        private val lockController: AppLockController,
        private val biometricKeyWrapper: BiometricKeyWrapper,
        private val storageInitializer: SecureStorageInitializer,
    ) : ViewModel() {
        private val preparing = MutableStateFlow(false)
        private val _feedback = MutableStateFlow(AppLockFeedback())
        val feedback: StateFlow<AppLockFeedback> = _feedback

        val uiState: StateFlow<AppLockUiState> =
            combine(keyVault.state, lockController.locked, preparing) { vault, uiLocked, isPreparing ->
                when {
                    vault is VaultState.NotSetUp -> AppLockUiState.Setup
                    vault is VaultState.Locked -> AppLockUiState.Locked(vault.biometricEnabled)
                    isPreparing -> AppLockUiState.Preparing
                    uiLocked -> AppLockUiState.Locked((vault as VaultState.Unlocked).biometricEnabled)
                    else -> AppLockUiState.Ready
                }
            }.stateIn(viewModelScope, SharingStarted.Eagerly, initialState())

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

        /** First-run setup: validates the pair, creates the vault, prepares storage. */
        fun setUp(
            password: String,
            confirm: String,
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
                    keyVault.setUp(password.toCharArray())
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
