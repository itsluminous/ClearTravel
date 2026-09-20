package com.itsluminous.cleartravel.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsluminous.cleartravel.core.data.repository.SettingsRepository
import com.itsluminous.cleartravel.core.model.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Exposes the DataStore-backed theme preference to the activity shell. The initial
 * value is [ThemeMode.SYSTEM] — the same theme the splash screen uses — so the
 * splash-to-content handoff never flashes the wrong theme while DataStore loads.
 */
@HiltViewModel
class ThemeViewModel
    @Inject
    constructor(
        settingsRepository: SettingsRepository,
    ) : ViewModel() {
        val themeMode: StateFlow<ThemeMode> =
            settingsRepository.themeMode
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), ThemeMode.SYSTEM)

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }
