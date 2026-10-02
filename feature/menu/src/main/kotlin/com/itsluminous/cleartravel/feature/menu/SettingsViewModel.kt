package com.itsluminous.cleartravel.feature.menu

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsluminous.cleartravel.core.data.repository.SettingsRepository
import com.itsluminous.cleartravel.core.model.ThemeMode
import com.itsluminous.cleartravel.core.model.TrainReminderLead
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** State + actions for the Settings screen: theme picker and the Notifications section (ADR-044). */
@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        private val settingsRepository: SettingsRepository,
    ) : ViewModel() {
        val themeMode: StateFlow<ThemeMode> =
            settingsRepository.themeMode
                .stateIn(viewModelScope, SharingStarted.Eagerly, ThemeMode.SYSTEM)

        fun setThemeMode(mode: ThemeMode) {
            viewModelScope.launch { settingsRepository.setThemeMode(mode) }
        }

        /** ADR-044: lead time of the train journey reminder; the app shell re-applies the worker schedule on change. */
        val trainReminderLead: StateFlow<TrainReminderLead> =
            settingsRepository.trainReminderLead
                .stateIn(viewModelScope, SharingStarted.Eagerly, TrainReminderLead.DEFAULT)

        fun setTrainReminderLead(lead: TrainReminderLead) {
            viewModelScope.launch { settingsRepository.setTrainReminderLead(lead) }
        }
    }
