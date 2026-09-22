package com.itsluminous.cleartravel.feature.documents

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsluminous.cleartravel.core.data.repository.TravelDocumentRepository
import com.itsluminous.cleartravel.core.model.EntityIds
import com.itsluminous.cleartravel.core.model.TravelDocument
import com.itsluminous.cleartravel.core.model.TravelDocumentType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject

/** One-shot events from [DocumentsViewModel] (snackbars). */
sealed interface DocumentsEvent {
    data class Added(
        val documentId: String,
    ) : DocumentsEvent

    /** The picked file could not be copied into app storage; nothing was saved. */
    data object AddFailed : DocumentsEvent

    data object Updated : DocumentsEvent

    data class Deleted(
        val name: String,
    ) : DocumentsEvent
}

/** The Documents tab: list + add/edit/delete (ADR-027). Reads are Room-only. */
@HiltViewModel
class DocumentsViewModel
    @Inject
    constructor(
        private val repository: TravelDocumentRepository,
        private val fileStore: DocumentFileStore,
        private val clock: Clock,
    ) : ViewModel() {
        /** Live documents, newest first; null while the first load is in flight. */
        val documents: StateFlow<List<TravelDocument>?> =
            repository
                .observeAll()
                .stateIn(viewModelScope, SharingStarted.Eagerly, null)

        private val eventChannel = Channel<DocumentsEvent>(Channel.BUFFERED)
        val events: Flow<DocumentsEvent> = eventChannel.receiveAsFlow()

        /**
         * Copies the picked file into `filesDir/documents/<id>.<ext>` and saves the row.
         * A blank [name] falls back to the type preset label supplied by the UI via
         * [fallbackName]. The row is written only after the copy succeeded.
         */
        fun addDocument(
            uriString: String,
            type: TravelDocumentType,
            name: String,
            fallbackName: String,
            expiryDate: LocalDate?,
            note: String = "",
        ) {
            viewModelScope.launch {
                val id = EntityIds.newId()
                val stored = fileStore.store(uriString, id)
                if (stored == null) {
                    eventChannel.send(DocumentsEvent.AddFailed)
                    return@launch
                }
                repository.save(
                    TravelDocument(
                        id = id,
                        name = name.trim().ifEmpty { fallbackName },
                        type = type,
                        filePath = stored.path,
                        mimeType = stored.mimeType,
                        addedAt = clock.instant(),
                        expiryDate = expiryDate,
                        note = note.trim(),
                    ),
                )
                eventChannel.send(DocumentsEvent.Added(id))
            }
        }

        /** Rename / retype / re-date an existing document; the file is untouched. */
        fun updateDetails(
            id: String,
            name: String,
            type: TravelDocumentType,
            expiryDate: LocalDate?,
            note: String,
        ) {
            val trimmed = name.trim()
            if (trimmed.isEmpty()) return
            viewModelScope.launch {
                val current = repository.getDocument(id) ?: return@launch
                repository.save(current.copy(name = trimmed, type = type, expiryDate = expiryDate, note = note.trim()))
                eventChannel.send(DocumentsEvent.Updated)
            }
        }

        /** Soft-deletes the row (ADR-002) and removes the stored file. */
        fun delete(id: String) {
            viewModelScope.launch {
                val current = repository.getDocument(id) ?: return@launch
                repository.delete(id)
                fileStore.delete(current.filePath)
                eventChannel.send(DocumentsEvent.Deleted(current.name))
            }
        }
    }
