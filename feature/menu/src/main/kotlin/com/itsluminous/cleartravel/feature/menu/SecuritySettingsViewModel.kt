package com.itsluminous.cleartravel.feature.menu

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsluminous.cleartravel.core.data.repository.SettingsRepository
import com.itsluminous.cleartravel.core.data.sync.BackgroundSyncStateStore
import com.itsluminous.cleartravel.core.data.sync.SyncWorkStatus
import com.itsluminous.cleartravel.core.security.biometric.BiometricKeyWrapper
import com.itsluminous.cleartravel.core.security.lock.LockTiming
import com.itsluminous.cleartravel.core.security.vault.KeyVault
import com.itsluminous.cleartravel.core.security.vault.VaultState
import com.itsluminous.cleartravel.core.security.vault.backgroundUnlockEnabled
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.crypto.Cipher
import javax.inject.Inject

/** One-shot outcomes of the security actions — the screen maps each to a snackbar. */
sealed interface SecurityEvent {
    data object PasswordChanged : SecurityEvent

    data object CurrentPasswordWrong : SecurityEvent

    data object BiometricEnabled : SecurityEvent

    data object BiometricDisabled : SecurityEvent

    /** The prompt failed / was refused, or the Keystore key could not be created. */
    data object BiometricSetupFailed : SecurityEvent

    /** ADR-043: "Allow sync while locked" turned on (DEK re-wrapped under the background key). */
    data object BackgroundSyncEnabled : SecurityEvent

    data object BackgroundSyncDisabled : SecurityEvent

    /** ADR-043: the confirmation password was wrong — the switch stays off. */
    data object BackgroundSyncPasswordWrong : SecurityEvent

    /** ADR-043: the Keystore refused to create the background key. */
    data object BackgroundSyncSetupFailed : SecurityEvent
}

/** Why a change-password attempt was rejected before touching the vault. */
enum class ChangePasswordError { TOO_SHORT, MISMATCH }

/**
 * Settings → Security (ADR-031): change the password (re-wraps the DEK only — no
 * data is re-encrypted), toggle biometric unlock (gated on a password existing, which
 * is always true past first run, and on strong biometrics being available on the
 * device), and pick the background lock timing.
 *
 * ADR-043: the opt-in "Allow sync while locked" switch — turning it ON asks for the
 * password again (the DEK is re-wrapped under a device-protected Keystore key; a
 * fresh confirmation makes that an explicit act, not a stray tap), turning it OFF
 * deletes the wrap and the Keystore key — and the read-only *Background sync*
 * status rows (last successful / skipped while locked) from the plaintext
 * [BackgroundSyncStateStore].
 */
@HiltViewModel
class SecuritySettingsViewModel
    @Inject
    constructor(
        private val keyVault: KeyVault,
        private val biometricKeyWrapper: BiometricKeyWrapper,
        private val settingsRepository: SettingsRepository,
        private val backgroundSyncStateStore: BackgroundSyncStateStore,
    ) : ViewModel() {
        data class UiState(
            /** A password exists (biometrics may be offered). */
            val hasPassword: Boolean = true,
            val biometricEnabled: Boolean = false,
            val lockTiming: LockTiming = LockTiming.DEFAULT,
            /** The change-password dialog is open. */
            val changingPassword: Boolean = false,
            val changeError: ChangePasswordError? = null,
            val busy: Boolean = false,
            /** ADR-043: a background-sync wrap exists ("Allow sync while locked" is on). */
            val backgroundSyncEnabled: Boolean = false,
            /** ADR-043: the password-confirmation dialog for turning the switch on is open. */
            val confirmingBackgroundSync: Boolean = false,
            /** ADR-043: per-kind last completed / last deferred, in `SyncWorkKind` order. */
            val syncStatuses: List<SyncWorkStatus> = emptyList(),
        )

        private val local = MutableStateFlow(UiState())
        val uiState: StateFlow<UiState> =
            combine(
                local,
                keyVault.state,
                settingsRepository.lockTiming,
                backgroundSyncStateStore.statuses,
            ) { state, vault, timing, statuses ->
                state.copy(
                    hasPassword = vault !is VaultState.NotSetUp,
                    biometricEnabled =
                        when (vault) {
                            is VaultState.Unlocked -> vault.biometricEnabled
                            is VaultState.Locked -> vault.biometricEnabled
                            VaultState.NotSetUp -> false
                        },
                    lockTiming = timing,
                    backgroundSyncEnabled = vault.backgroundUnlockEnabled,
                    syncStatuses = statuses,
                )
            }.stateIn(viewModelScope, SharingStarted.Eagerly, UiState())

        private val eventChannel = Channel<SecurityEvent>(Channel.BUFFERED)
        val events: Flow<SecurityEvent> = eventChannel.receiveAsFlow()

        fun openChangePassword() = local.update { it.copy(changingPassword = true, changeError = null) }

        fun dismissChangePassword() = local.update { it.copy(changingPassword = false, changeError = null) }

        fun clearChangeError() = local.update { it.copy(changeError = null) }

        /** Verifies the pair, then re-wraps the DEK under the new password. */
        fun changePassword(
            current: String,
            new: String,
            confirm: String,
        ) {
            if (local.value.busy) return
            val error =
                when {
                    new.length < MIN_PASSWORD_LENGTH -> ChangePasswordError.TOO_SHORT
                    new != confirm -> ChangePasswordError.MISMATCH
                    else -> null
                }
            if (error != null) {
                local.update { it.copy(changeError = error) }
                return
            }
            viewModelScope.launch {
                local.update { it.copy(busy = true) }
                try {
                    if (keyVault.changePassword(current.toCharArray(), new.toCharArray())) {
                        local.update { it.copy(changingPassword = false) }
                        eventChannel.send(SecurityEvent.PasswordChanged)
                    } else {
                        eventChannel.send(SecurityEvent.CurrentPasswordWrong)
                    }
                } finally {
                    local.update { it.copy(busy = false) }
                }
            }
        }

        /**
         * Step 1 of enabling biometrics: the ENCRYPT cipher to authenticate through
         * `BiometricPrompt`; null when the Keystore refused (event sent).
         */
        fun biometricEnrolCipher(): Cipher? =
            try {
                biometricKeyWrapper.newEncryptCipher()
            } catch (e: Exception) {
                viewModelScope.launch { eventChannel.send(SecurityEvent.BiometricSetupFailed) }
                null
            }

        /** Step 2: wraps the DEK with the cipher the user just authenticated. */
        fun enableBiometric(authenticatedCipher: Cipher) {
            viewModelScope.launch {
                try {
                    keyVault.enableBiometric(authenticatedCipher)
                    eventChannel.send(SecurityEvent.BiometricEnabled)
                } catch (e: Exception) {
                    biometricKeyWrapper.deleteKey()
                    eventChannel.send(SecurityEvent.BiometricSetupFailed)
                }
            }
        }

        fun biometricPromptFailed() {
            biometricKeyWrapper.deleteKey()
            viewModelScope.launch { eventChannel.send(SecurityEvent.BiometricSetupFailed) }
        }

        fun disableBiometric() {
            viewModelScope.launch {
                keyVault.disableBiometric()
                biometricKeyWrapper.deleteKey()
                eventChannel.send(SecurityEvent.BiometricDisabled)
            }
        }

        fun setLockTiming(timing: LockTiming) {
            viewModelScope.launch { settingsRepository.setLockTiming(timing) }
        }

        // ---- ADR-043: allow sync while locked ----

        /** The switch was flipped ON: open the password confirmation (nothing changes yet). */
        fun requestEnableBackgroundSync() = local.update { it.copy(confirmingBackgroundSync = true) }

        fun dismissBackgroundSyncConfirmation() = local.update { it.copy(confirmingBackgroundSync = false) }

        /** Confirmation submitted: verify the password and re-wrap the DEK under the background key. */
        fun enableBackgroundSync(password: String) {
            if (local.value.busy || password.isEmpty()) return
            viewModelScope.launch {
                local.update { it.copy(busy = true) }
                try {
                    val ok =
                        try {
                            keyVault.enableBackgroundUnlock(password.toCharArray())
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            keyVault.disableBackgroundUnlock()
                            local.update { it.copy(confirmingBackgroundSync = false) }
                            eventChannel.send(SecurityEvent.BackgroundSyncSetupFailed)
                            return@launch
                        }
                    if (ok) {
                        local.update { it.copy(confirmingBackgroundSync = false) }
                        eventChannel.send(SecurityEvent.BackgroundSyncEnabled)
                    } else {
                        eventChannel.send(SecurityEvent.BackgroundSyncPasswordWrong)
                    }
                } finally {
                    local.update { it.copy(busy = false) }
                }
            }
        }

        /** The switch was flipped OFF: drop the wrap and the Keystore key. */
        fun disableBackgroundSync() {
            viewModelScope.launch {
                keyVault.disableBackgroundUnlock()
                eventChannel.send(SecurityEvent.BackgroundSyncDisabled)
            }
        }

        companion object {
            /** Mirrors feature:applock's setup rule (features cannot share code directly). */
            const val MIN_PASSWORD_LENGTH = 8
        }
    }
