package com.itsluminous.cleartravel.feature.checklist

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsluminous.cleartravel.core.data.repository.ChecklistPresetRepository
import com.itsluminous.cleartravel.core.data.repository.ChecklistRepository
import com.itsluminous.cleartravel.core.data.share.ShareLinkCodec
import com.itsluminous.cleartravel.core.data.share.SharePayloadMappers
import com.itsluminous.cleartravel.core.data.share.ShareUrlResult
import com.itsluminous.cleartravel.core.designsystem.component.moved
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

    /** ADR-039: the share link is ready — hand it to the share sheet with the checklist's name. */
    data class ShareReady(
        val checklistName: String,
        val url: String,
    ) : ChecklistDetailEvent

    /** ADR-039: the link would exceed the URL ceiling — suggest fewer items. */
    data object ShareTooLong : ChecklistDetailEvent
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

        /** Replaces an item's text (trimmed); blank or unchanged text is ignored. */
        fun renameItem(
            itemId: String,
            text: String,
        ) {
            val trimmed = text.trim()
            val item = items.value.firstOrNull { it.id == itemId } ?: return
            if (trimmed.isEmpty() || trimmed == item.text) return
            viewModelScope.launch { checklistRepository.saveItem(item.copy(text = trimmed)) }
        }

        /**
         * Drag-to-reorder drop: moves the item at [from] to [to] (ADR-021). Only the rows
         * in the affected range are rewritten — each takes the `sortOrder` of the slot it
         * now occupies, so the set of sort keys is unchanged. Out-of-range or same-index
         * moves are no-ops.
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
            viewModelScope.launch { checklistRepository.saveItems(changed) }
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

        /**
         * ADR-039: builds the self-contained share link — checklist + every item with
         * its CHECKED state, under the original ids so a re-share updates the
         * recipient's copy — and reports it as an event.
         */
        fun share() {
            val current = checklist.value ?: return
            val payload = SharePayloadMappers.toPayload(current, items.value)
            val event =
                when (val result = ShareLinkCodec.buildShareUrl(payload)) {
                    is ShareUrlResult.Ok -> ChecklistDetailEvent.ShareReady(checklistName = current.name, url = result.url)
                    is ShareUrlResult.TooLong -> ChecklistDetailEvent.ShareTooLong
                }
            viewModelScope.launch { eventChannel.send(event) }
        }

        fun deleteChecklist() {
            viewModelScope.launch {
                checklistRepository.deleteChecklist(checklistId)
                eventChannel.send(ChecklistDetailEvent.Deleted)
            }
        }
    }
