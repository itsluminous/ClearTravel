package com.itsluminous.cleartravel.feature.checklist

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsluminous.cleartravel.core.data.repository.ChecklistPresetRepository
import com.itsluminous.cleartravel.core.data.repository.ChecklistRepository
import com.itsluminous.cleartravel.core.model.Checklist
import com.itsluminous.cleartravel.core.model.ChecklistItem
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

/** Nav argument key carrying the checklist id into the detail screen. */
const val CHECKLIST_ID_ARG = "checklistId"

/** One-shot events from [ChecklistDetailViewModel]. */
sealed interface ChecklistDetailEvent {
    /** A preset append finished; [addedCount] can be less than the preset size (dedupe). */
    data class PresetAppended(
        val addedCount: Int,
    ) : ChecklistDetailEvent

    /** The checklist was deleted; navigate back to the list. */
    data object Deleted : ChecklistDetailEvent
}

/** State + actions for one checklist's full-screen detail view. */
@HiltViewModel
class ChecklistDetailViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val checklistRepository: ChecklistRepository,
        presetRepository: ChecklistPresetRepository,
    ) : ViewModel() {
        private val checklistId: String = checkNotNull(savedStateHandle[CHECKLIST_ID_ARG])

        val checklist: StateFlow<Checklist?> =
            checklistRepository
                .observeChecklist(checklistId)
                .stateIn(viewModelScope, SharingStarted.Eagerly, null)

        /** Items ordered by `sortOrder`. */
        val items: StateFlow<List<ChecklistItem>> =
            checklistRepository
                .observeItems(checklistId)
                .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

        /** Presets offered by the append-preset picker (multi-append, ADR-006). */
        val presets: StateFlow<List<ChecklistPreset>> =
            presetRepository
                .observePresets()
                .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

        private val eventChannel = Channel<ChecklistDetailEvent>(Channel.BUFFERED)
        val events: Flow<ChecklistDetailEvent> = eventChannel.receiveAsFlow()

        fun setItemChecked(
            itemId: String,
            checked: Boolean,
        ) {
            viewModelScope.launch { checklistRepository.setItemChecked(itemId, checked) }
        }

        /** Appends a new unchecked item after the current last one. */
        fun addItem(text: String) {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return
            viewModelScope.launch {
                val nextOrder = (items.value.maxOfOrNull { it.sortOrder } ?: -1) + 1
                checklistRepository.saveItem(
                    ChecklistItem(checklistId = checklistId, text = trimmed, sortOrder = nextOrder),
                )
            }
        }

        fun removeItem(itemId: String) {
            viewModelScope.launch { checklistRepository.deleteItem(itemId) }
        }

        /**
         * Moves an item one position up or down by swapping `sortOrder` with its
         * neighbour (up/down buttons instead of drag — see the milestone notes).
         */
        fun moveItem(
            itemId: String,
            up: Boolean,
        ) {
            val current = items.value
            val index = current.indexOfFirst { it.id == itemId }
            if (index < 0) return
            val neighbourIndex = if (up) index - 1 else index + 1
            if (neighbourIndex !in current.indices) return
            val item = current[index]
            val neighbour = current[neighbourIndex]
            viewModelScope.launch {
                checklistRepository.saveItems(
                    listOf(
                        item.copy(sortOrder = neighbour.sortOrder),
                        neighbour.copy(sortOrder = item.sortOrder),
                    ),
                )
            }
        }

        /**
         * Appends [presetId]'s items to this checklist (cumulative multi-append with
         * dedupe-by-text, ADR-006) and reports how many were actually added.
         */
        fun appendPreset(presetId: String) {
            viewModelScope.launch {
                val added = checklistRepository.appendPreset(checklistId, presetId)
                eventChannel.send(ChecklistDetailEvent.PresetAppended(added.size))
            }
        }

        fun deleteChecklist() {
            viewModelScope.launch {
                checklistRepository.deleteChecklist(checklistId)
                eventChannel.send(ChecklistDetailEvent.Deleted)
            }
        }
    }
