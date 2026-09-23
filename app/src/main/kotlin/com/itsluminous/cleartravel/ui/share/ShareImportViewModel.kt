package com.itsluminous.cleartravel.ui.share

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsluminous.cleartravel.core.data.share.ChecklistSharePayload
import com.itsluminous.cleartravel.core.data.share.ShareImportPreview
import com.itsluminous.cleartravel.core.data.share.ShareImportResult
import com.itsluminous.cleartravel.core.data.share.ShareLinkError
import com.itsluminous.cleartravel.core.data.share.SharedContentImporter
import com.itsluminous.cleartravel.core.data.share.TripSharePayload
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The import dialog's state machine (ADR-039). */
sealed interface ShareImportUiState {
    data object Idle : ShareImportUiState

    /** Reading the recipient's copy to decide between "Add" and "Update". */
    data object Loading : ShareImportUiState

    /** Waiting for the user to confirm; [preview] drives the dialog text. */
    data class Confirm(
        val preview: ShareImportPreview,
    ) : ShareImportUiState

    /** Writing. */
    data object Importing : ShareImportUiState

    /** Written; the shell lands on the result and resets. */
    data class Done(
        val result: ShareImportResult,
    ) : ShareImportUiState

    /** The link could not be used; [error] picks the explanation. */
    data class Failed(
        val error: ShareLinkError,
    ) : ShareImportUiState
}

/** A trip or checklist payload awaiting confirmation. */
sealed interface ShareImportRequest {
    data class Trip(
        val payload: TripSharePayload,
    ) : ShareImportRequest

    data class Checklist(
        val payload: ChecklistSharePayload,
    ) : ShareImportRequest
}

/**
 * Drives the confirm → import flow for a shared trip/checklist (ADR-039). The
 * activity feeds it a decoded payload ([start]) or a decode failure ([fail]); the
 * host composable renders the dialog and reports [ShareImportUiState.Done] up.
 */
@HiltViewModel
class ShareImportViewModel
    @Inject
    constructor(
        private val importer: SharedContentImporter,
    ) : ViewModel() {
        private val state = MutableStateFlow<ShareImportUiState>(ShareImportUiState.Idle)
        val uiState: StateFlow<ShareImportUiState> = state.asStateFlow()

        private var request: ShareImportRequest? = null

        fun start(incoming: ShareImportRequest) {
            request = incoming
            state.value = ShareImportUiState.Loading
            viewModelScope.launch {
                val preview =
                    when (incoming) {
                        is ShareImportRequest.Trip -> importer.preview(incoming.payload)
                        is ShareImportRequest.Checklist -> importer.preview(incoming.payload)
                    }
                // A newer request may have superseded this one while we read.
                if (request === incoming) state.value = ShareImportUiState.Confirm(preview)
            }
        }

        fun fail(error: ShareLinkError) {
            request = null
            state.value = ShareImportUiState.Failed(error)
        }

        fun confirm() {
            val current = request ?: return
            if (state.value !is ShareImportUiState.Confirm) return
            state.value = ShareImportUiState.Importing
            viewModelScope.launch {
                val result =
                    when (current) {
                        is ShareImportRequest.Trip -> importer.import(current.payload)
                        is ShareImportRequest.Checklist -> importer.import(current.payload)
                    }
                request = null
                state.value = ShareImportUiState.Done(result)
            }
        }

        fun reset() {
            request = null
            state.value = ShareImportUiState.Idle
        }
    }
