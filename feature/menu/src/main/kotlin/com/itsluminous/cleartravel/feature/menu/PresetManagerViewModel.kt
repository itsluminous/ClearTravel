package com.itsluminous.cleartravel.feature.menu

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsluminous.cleartravel.core.data.repository.ChecklistPresetRepository
import com.itsluminous.cleartravel.core.model.ChecklistPreset
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One-shot events from [PresetManagerViewModel]. */
sealed interface PresetManagerEvent {
    /** A preset was created; navigate to its editor. */
    data class Created(
        val presetId: String,
    ) : PresetManagerEvent

    /** A preset was duplicated (copy semantics — never mutates checklists, ADR-006). */
    data object Duplicated : PresetManagerEvent

    data object Deleted : PresetManagerEvent
}

/**
 * State + actions for Menu → Manage presets. Built-in presets are protected from
 * edit/delete; duplicating one produces an editable user copy (see milestone notes).
 */
@HiltViewModel
class PresetManagerViewModel
    @Inject
    constructor(
        private val presetRepository: ChecklistPresetRepository,
    ) : ViewModel() {
        /** Live presets (built-ins first); null while the first load is in flight. */
        val presets: StateFlow<List<ChecklistPreset>?> =
            presetRepository
                .observePresets()
                .stateIn(viewModelScope, SharingStarted.Eagerly, null)

        private val eventChannel = Channel<PresetManagerEvent>(Channel.BUFFERED)
        val events: Flow<PresetManagerEvent> = eventChannel.receiveAsFlow()

        fun createPreset(name: String) {
            val trimmed = name.trim()
            if (trimmed.isEmpty()) return
            viewModelScope.launch {
                val saved = presetRepository.save(ChecklistPreset(name = trimmed))
                eventChannel.send(PresetManagerEvent.Created(saved.id))
            }
        }

        fun duplicatePreset(
            presetId: String,
            newName: String,
        ) {
            viewModelScope.launch {
                val copy = presetRepository.duplicatePreset(presetId, newName)
                if (copy != null) {
                    eventChannel.send(PresetManagerEvent.Duplicated)
                }
            }
        }

        /** Deletes a USER preset; built-ins are protected (UI hides the action too). */
        fun deletePreset(preset: ChecklistPreset) {
            if (preset.builtIn) return
            viewModelScope.launch {
                presetRepository.deletePreset(preset.id)
                eventChannel.send(PresetManagerEvent.Deleted)
            }
        }
    }
