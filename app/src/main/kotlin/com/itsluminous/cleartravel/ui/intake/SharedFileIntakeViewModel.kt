package com.itsluminous.cleartravel.ui.intake

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsluminous.cleartravel.core.ocr.OcrPrefillService
import com.itsluminous.cleartravel.core.ocr.model.BoardingPassSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Seam over the OCR pipeline for the intake auto-detect, so the ViewModel stays
 * plain-JVM testable. Takes the URI as a string (no `android.net.Uri` in tests).
 */
interface SharedDocProbe {
    suspend fun probe(uriString: String): SharedDocProbeResult
}

/**
 * Production probe over the PUBLIC `OcrPrefillService` API — reuses the existing
 * three import pipelines rather than duplicating extraction logic. The boarding-pass
 * pass runs first because it is barcode-first: a decoded BCBP short-circuits the
 * (costlier) OCR passes entirely. Otherwise the train and booking extractors run
 * concurrently over the same file. Follow-up: a single-pass probe inside
 * `core:ocr` (one OCR, three extractors) would cut this to one recognition run.
 */
@Singleton
class OcrSharedDocProbe
    @Inject
    constructor(
        private val ocrPrefillService: OcrPrefillService,
    ) : SharedDocProbe {
        override suspend fun probe(uriString: String): SharedDocProbeResult =
            coroutineScope {
                val uri = Uri.parse(uriString)
                val boardingPass = runCatching { ocrPrefillService.prefillBoardingPass(uri) }.getOrNull()
                if (boardingPass?.source == BoardingPassSource.BARCODE) {
                    return@coroutineScope SharedDocProbeResult(boardingPass = boardingPass)
                }
                val train = async { runCatching { ocrPrefillService.prefillTrainTicket(uri) }.getOrNull() }
                val booking = async { runCatching { ocrPrefillService.prefillBookingConfirmation(uri) }.getOrNull() }
                SharedDocProbeResult(
                    trainTicket = train.await() ?: SharedDocProbeResult().trainTicket,
                    boardingPass = boardingPass ?: SharedDocProbeResult().boardingPass,
                    bookingConfirmation = booking.await() ?: SharedDocProbeResult().bookingConfirmation,
                )
            }
    }

/** Where a confirmed intake choice sends the file — consumed by `MainActivity`. */
sealed interface IntakeRoute {
    val uri: String

    data class TrainTicket(
        override val uri: String,
    ) : IntakeRoute

    data class FlightBoardingPass(
        override val uri: String,
    ) : IntakeRoute

    data class FlightBookingConfirmation(
        override val uri: String,
    ) : IntakeRoute
}

data class SharedFileIntakeUiState(
    val uri: String? = null,
    /** True while the auto-detect runs; the dialog shows a progress line meanwhile. */
    val detecting: Boolean = false,
    /** Auto-detected preselection; null = the user must pick. */
    val suggested: SharedDocType? = null,
    /** The user's current selection (starts as [suggested] once detection ends). */
    val selected: SharedDocType? = null,
    /** Set once the user confirms — the shell routes and then calls [SharedFileIntakeViewModel.reset]. */
    val route: IntakeRoute? = null,
) {
    val active: Boolean get() = uri != null
}

/**
 * "What's this file?" intake for PDFs/images arriving through the share sheet: runs
 * the cheap auto-detect to PRESELECT the likely document type, lets the user
 * confirm or override, and exposes the resulting [IntakeRoute] for the shell.
 */
@HiltViewModel
class SharedFileIntakeViewModel
    @Inject
    constructor(
        private val probe: SharedDocProbe,
    ) : ViewModel() {
        private val state = MutableStateFlow(SharedFileIntakeUiState())
        val uiState: StateFlow<SharedFileIntakeUiState> = state.asStateFlow()

        /** Starts intake for [uriString]: opens the dialog and kicks off detection. */
        fun start(uriString: String) {
            state.value = SharedFileIntakeUiState(uri = uriString, detecting = true)
            viewModelScope.launch {
                val suggested = pickLikelyDocType(probe.probe(uriString))
                state.update { current ->
                    // The intake may have been cancelled or restarted meanwhile.
                    if (current.uri != uriString) current else current.copy(detecting = false, suggested = suggested, selected = suggested)
                }
            }
        }

        fun select(type: SharedDocType) = state.update { it.copy(selected = type) }

        /** Confirms the current selection; no-op until something is selected. */
        fun confirm() {
            state.update { current ->
                val uri = current.uri ?: return@update current
                val type = current.selected ?: return@update current
                current.copy(route = routeFor(type, uri))
            }
        }

        /** Cancels the dialog or clears a consumed route. */
        fun reset() {
            state.value = SharedFileIntakeUiState()
        }

        private fun routeFor(
            type: SharedDocType,
            uri: String,
        ): IntakeRoute =
            when (type) {
                SharedDocType.TRAIN_TICKET -> IntakeRoute.TrainTicket(uri)
                SharedDocType.BOARDING_PASS -> IntakeRoute.FlightBoardingPass(uri)
                SharedDocType.BOOKING_CONFIRMATION -> IntakeRoute.FlightBookingConfirmation(uri)
            }
    }
