package com.itsluminous.cleartravel.feature.menu

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsluminous.cleartravel.core.data.repository.ChecklistPresetRepository
import com.itsluminous.cleartravel.core.designsystem.component.moved
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
 * State + actions for one preset's editor. Built-in AND user presets are fully
 * editable here (ADR-021 supersedes the ADR-010 read-only policy); the tombstone-aware
 * seeder never overwrites an edited built-in. Editing a preset never mutates checklists
 * created from it (copy semantics, ADR-006).
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
            if (trimmed.isEmpty() || trimmed == current.name) return
            viewModelScope.launch { presetRepository.save(current.copy(name = trimmed)) }
        }

        fun addItem(text: String) {
            if (preset.value == null) return
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return
            viewModelScope.launch {
                val nextOrder = (items.value.maxOfOrNull { it.sortOrder } ?: -1) + 1
                presetRepository.saveItems(
                    listOf(ChecklistPresetItem(presetId = presetId, text = trimmed, sortOrder = nextOrder)),
                )
            }
        }

        fun removeItem(itemId: String) {
            viewModelScope.launch { presetRepository.deleteItem(itemId) }
        }

        /** Replaces a template item's text (trimmed); blank or unchanged text is ignored. */
        fun renameItem(
            itemId: String,
            text: String,
        ) {
            val trimmed = text.trim()
            val item = items.value.firstOrNull { it.id == itemId } ?: return
            if (trimmed.isEmpty() || trimmed == item.text) return
            viewModelScope.launch { presetRepository.saveItems(listOf(item.copy(text = trimmed))) }
        }

        /**
         * Drag-to-reorder drop: moves the item at [from] to [to] (ADR-021). Rows in the
         * affected range take the `sortOrder` of the slot they now occupy; out-of-range
         * or same-index moves are no-ops.
         */
        fun moveItem(
            from: Int,
            to: Int,
        ) {
            val current = items.value
            if (from == to || from !in current.indices || to !in current.indices) return
            val reordered = current.moved(from, to)
            val changed =
                (minOf(from, to)..maxOf(from, to)).map { index ->
                    reordered[index].copy(sortOrder = current[index].sortOrder)
                }
            viewModelScope.launch { presetRepository.saveItems(changed) }
        }
    }
