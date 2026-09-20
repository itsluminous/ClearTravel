package com.itsluminous.cleartravel.feature.menu

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsluminous.cleartravel.core.google.auth.GoogleAccountManager
import com.itsluminous.cleartravel.core.google.auth.GoogleFeature
import com.itsluminous.cleartravel.core.google.auth.GoogleFeatureSettings
import com.itsluminous.cleartravel.core.google.auth.GoogleLinkException
import com.itsluminous.cleartravel.core.google.auth.GoogleLinkState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One-shot events from [GoogleSettingsViewModel] — the screen maps each to a snackbar. */
sealed interface GoogleSettingsEvent {
    data class Linked(
        val email: String,
    ) : GoogleSettingsEvent

    data object Disconnected : GoogleSettingsEvent

    data object LinkFailed : GoogleSettingsEvent

    data object ScopeDenied : GoogleSettingsEvent
}

/**
 * State + actions of the Settings "Google account" section (spec feature 5): link/
 * unlink with the account shown, and the three feature toggles, each triggering its
 * INCREMENTAL scope request. Everything is disabled-with-explanation when no web
 * client id is configured; the ViewModel is tested against a fake
 * [GoogleAccountManager] — never live APIs.
 */
@HiltViewModel
class GoogleSettingsViewModel
    @Inject
    constructor(
        private val accountManager: GoogleAccountManager,
    ) : ViewModel() {
        data class UiState(
            val inProgress: Boolean = false,
            /** Non-null: the consent sheet must be launched via an IntentSender. */
            val consentIntent: PendingIntent? = null,
            val showDisconnectDialog: Boolean = false,
        )

        val linkState: StateFlow<GoogleLinkState> =
            accountManager.linkState
                .stateIn(viewModelScope, SharingStarted.Eagerly, GoogleLinkState.NotConfigured)

        val featureSettings: StateFlow<GoogleFeatureSettings> =
            accountManager.featureSettings
                .stateIn(viewModelScope, SharingStarted.Eagerly, GoogleFeatureSettings())

        private val _uiState = MutableStateFlow(UiState())
        val uiState: StateFlow<UiState> = _uiState.asStateFlow()

        private val eventChannel = Channel<GoogleSettingsEvent>(Channel.BUFFERED)
        val events: Flow<GoogleSettingsEvent> = eventChannel.receiveAsFlow()

        /** Runs the account-picker flow; [activityContext] MUST be an Activity context. */
        fun link(activityContext: Context) {
            if (_uiState.value.inProgress) return
            viewModelScope.launch {
                _uiState.update { it.copy(inProgress = true) }
                accountManager
                    .link(activityContext)
                    .onSuccess { eventChannel.send(GoogleSettingsEvent.Linked(it.email)) }
                    .onFailure { onFailure(it, GoogleSettingsEvent.LinkFailed) }
                _uiState.update { it.copy(inProgress = false) }
            }
        }

        /** Turns a feature toggle on/off, requesting its incremental scope when needed. */
        fun setFeatureEnabled(
            feature: GoogleFeature,
            enabled: Boolean,
            activityContext: Context,
        ) {
            if (_uiState.value.inProgress) return
            viewModelScope.launch {
                _uiState.update { it.copy(inProgress = true) }
                accountManager
                    .setFeatureEnabled(feature, enabled, activityContext)
                    .onFailure { onFailure(it, GoogleSettingsEvent.ScopeDenied) }
                _uiState.update { it.copy(inProgress = false) }
            }
        }

        /** The consent sheet returned; completes the pending toggle. */
        fun onConsentResult(resultIntent: Intent?) {
            viewModelScope.launch {
                _uiState.update { it.copy(consentIntent = null) }
                accountManager
                    .completeScopeConsent(resultIntent)
                    .onFailure { onFailure(it, GoogleSettingsEvent.ScopeDenied) }
            }
        }

        /** The screen has launched the consent IntentSender; stop re-launching it. */
        fun consentLaunched() {
            _uiState.update { it.copy(consentIntent = null) }
        }

        fun requestDisconnect() {
            _uiState.update { it.copy(showDisconnectDialog = true) }
        }

        fun dismissDisconnect() {
            _uiState.update { it.copy(showDisconnectDialog = false) }
        }

        /** Disconnects; [deleteCalendar] also removes the ClearTravel calendar. */
        fun confirmDisconnect(deleteCalendar: Boolean) {
            viewModelScope.launch {
                _uiState.update { it.copy(showDisconnectDialog = false, inProgress = true) }
                accountManager.unlink(deleteCalendar)
                eventChannel.send(GoogleSettingsEvent.Disconnected)
                _uiState.update { it.copy(inProgress = false) }
            }
        }

        private suspend fun onFailure(
            error: Throwable,
            fallback: GoogleSettingsEvent,
        ) {
            when (error) {
                is GoogleLinkException.NeedsScopeConsent ->
                    _uiState.update { it.copy(consentIntent = error.pendingIntent) }
                is GoogleLinkException.Cancelled -> Unit // User backed out — no snackbar.
                else -> eventChannel.send(fallback)
            }
        }
    }
