package com.itsluminous.cleartravel.feature.menu

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsluminous.cleartravel.core.data.repository.ChecklistPresetRepository
import com.itsluminous.cleartravel.core.model.ChecklistPreset
import com.itsluminous.cleartravel.core.model.ChecklistPresetItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Nav argument key carrying the preset id into the editor screen. */
const val PRESET_ID_ARG = "presetId"

/**
 * State + actions for one preset's editor. Built-in presets are READ-ONLY here —
 * every mutation is guarded (the UI shows a duplicate-to-customize banner instead).
 * Editing a preset never mutates checklists created from it (copy semantics, ADR-006).
 */
@HiltViewModel
class PresetEditViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val presetRepository: ChecklistPresetRepository,
    ) : ViewModel() {
        private val presetId: String = checkNotNull(savedStateHandle[PRESET_ID_ARG])

        val preset: StateFlow<ChecklistPreset?> =
            presetRepository
                .observePresets()
                .map { presets -> presets.firstOrNull { it.id == presetId } }
                .stateIn(viewModelScope, SharingStarted.Eagerly, null)

        /** Template items ordered by `sortOrder`. */
        val items: StateFlow<List<ChecklistPresetItem>> =
            presetRepository
                .observeItems(presetId)
                .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

        fun rename(name: String) {
            val current = preset.value ?: return
            val trimmed = name.trim()
            if (current.builtIn || trimmed.isEmpty() || trimmed == current.name) return
            viewModelScope.launch { presetRepository.save(current.copy(name = trimmed)) }
        }

        fun addItem(text: String) {
            val current = preset.value ?: return
            val trimmed = text.trim()
            if (current.builtIn || trimmed.isEmpty()) return
            viewModelScope.launch {
                val nextOrder = (items.value.maxOfOrNull { it.sortOrder } ?: -1) + 1
                presetRepository.saveItems(
                    listOf(ChecklistPresetItem(presetId = presetId, text = trimmed, sortOrder = nextOrder)),
                )
            }
        }

        fun removeItem(itemId: String) {
            if (preset.value?.builtIn != false) return
            viewModelScope.launch { presetRepository.deleteItem(itemId) }
        }

        /** Moves an item one position up or down by swapping `sortOrder`. */
        fun moveItem(
            itemId: String,
            up: Boolean,
        ) {
            if (preset.value?.builtIn != false) return
            val current = items.value
            val index = current.indexOfFirst { it.id == itemId }
            if (index < 0) return
            val neighbourIndex = if (up) index - 1 else index + 1
            if (neighbourIndex !in current.indices) return
            val item = current[index]
            val neighbour = current[neighbourIndex]
            viewModelScope.launch {
                presetRepository.saveItems(
                    listOf(
                        item.copy(sortOrder = neighbour.sortOrder),
                        neighbour.copy(sortOrder = item.sortOrder),
                    ),
                )
            }
        }
    }
