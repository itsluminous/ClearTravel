package com.itsluminous.cleartravel.feature.checklist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsluminous.cleartravel.core.data.repository.ChecklistPresetRepository
import com.itsluminous.cleartravel.core.data.repository.ChecklistRepository
import com.itsluminous.cleartravel.core.model.Checklist
import com.itsluminous.cleartravel.core.model.ChecklistPreset
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One card on the checklist list: the checklist plus its packing progress. */
data class ChecklistRow(
    val checklist: Checklist,
    val doneCount: Int,
    val totalCount: Int,
)

/** One-shot events from [ChecklistListViewModel]. */
sealed interface ChecklistListEvent {
    /** A checklist was created; navigate to its detail screen. */
    data class Created(
        val checklistId: String,
    ) : ChecklistListEvent
}

/** State + actions for the checklist list screen (all checklists, incl. standalone). */
@HiltViewModel
class ChecklistListViewModel
    @Inject
    constructor(
        private val checklistRepository: ChecklistRepository,
        presetRepository: ChecklistPresetRepository,
    ) : ViewModel() {
        /** All checklists with live progress; null while the first load is in flight. */
        @OptIn(ExperimentalCoroutinesApi::class)
        val checklists: StateFlow<List<ChecklistRow>?> =
            checklistRepository
                .observeChecklists()
                .flatMapLatest { lists -> progressRows(lists) }
                .stateIn(viewModelScope, SharingStarted.Eagerly, null)

        /** Presets offered by the "start from" picker in the create dialog. */
        val presets: StateFlow<List<ChecklistPreset>> =
            presetRepository
                .observePresets()
                .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

        private val eventChannel = Channel<ChecklistListEvent>(Channel.BUFFERED)
        val events: Flow<ChecklistListEvent> = eventChannel.receiveAsFlow()

        /** Creates a checklist, optionally seeding it from [presetId] (ADR-006 copy). */
        fun createChecklist(
            name: String,
            presetId: String?,
        ) {
            val trimmed = name.trim()
            if (trimmed.isEmpty()) return
            viewModelScope.launch {
                val saved = checklistRepository.save(Checklist(name = trimmed))
                if (presetId != null) {
                    checklistRepository.appendPreset(saved.id, presetId)
                }
                eventChannel.send(ChecklistListEvent.Created(saved.id))
            }
        }

        private fun progressRows(lists: List<Checklist>): Flow<List<ChecklistRow>> {
            if (lists.isEmpty()) return flowOf(emptyList())
            val rowFlows =
                lists.map { checklist ->
                    checklistRepository.observeItems(checklist.id).map { items ->
                        ChecklistRow(
                            checklist = checklist,
                            doneCount = items.count { it.checked },
                            totalCount = items.size,
                        )
                    }
                }
            return combine(rowFlows) { rows -> rows.toList() }
        }
    }
