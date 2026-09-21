package com.itsluminous.cleartravel.feature.trains.form

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsluminous.cleartravel.core.data.repository.TrainRepository
import com.itsluminous.cleartravel.core.model.EntityIds
import com.itsluminous.cleartravel.core.model.TrainPassenger
import com.itsluminous.cleartravel.core.model.TrainTicket
import com.itsluminous.cleartravel.core.ocr.ExtractionConfidence
import com.itsluminous.cleartravel.core.ocr.model.ExtractedField
import com.itsluminous.cleartravel.core.ocr.model.TrainTicketExtraction
import com.itsluminous.cleartravel.feature.trains.isValidPnr
import com.itsluminous.cleartravel.feature.trains.prefill.TrainPrefillSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject

/** Form fields that can carry a prefill-confidence marker. */
enum class TrainFormField {
    PNR,
    TRAIN_NUMBER,
    TRAIN_NAME,
    JOURNEY_DATE,
    FROM_STATION,
    TO_STATION,
    TRAVEL_CLASS,
    QUOTA,
}

/** One editable passenger row. [existingId] is null for rows added in this session. */
data class PassengerFormRow(
    val rowKey: String = EntityIds.newId(),
    val existingId: String? = null,
    val name: String = "",
    val coach: String = "",
    val seatBerth: String = "",
    val bookingStatus: String = "",
    val currentStatus: String = "",
    val lowConfidence: Boolean = false,
)

data class TrainFormUiState(
    val editingTicketId: String? = null,
    val pnr: String = "",
    val pnrError: Boolean = false,
    val trainNumber: String = "",
    val trainName: String = "",
    val journeyDate: LocalDate? = null,
    val fromStation: String = "",
    val toStation: String = "",
    val travelClass: String = "",
    val quota: String = "",
    val passengers: List<PassengerFormRow> = listOf(PassengerFormRow()),
    /** Prefill confidence per field — [ExtractionConfidence.LOW] fields get a marker. */
    val confidences: Map<TrainFormField, ExtractionConfidence> = emptyMap(),
    val prefilled: Boolean = false,
    val saving: Boolean = false,
) {
    val isEdit: Boolean get() = editingTicketId != null

    fun lowConfidence(field: TrainFormField): Boolean = confidences[field] == ExtractionConfidence.LOW
}

/** One-shot events for the hosting composable (snackbars / navigation). */
sealed interface TrainFormEvent {
    data class Saved(
        val ticketId: String,
        val pnr: String = "",
        /**
         * True for a PNR-only quick add (new ticket, nothing but the PNR filled):
         * the host should open the PNR check WebView directly so the first status
         * fetch backfills train, date, stations and passengers (ADR-023).
         */
        val openPnrCheck: Boolean = false,
    ) : TrainFormEvent

    /** A file import produced nothing — the form stays blank; show a hint. */
    data object PrefillEmpty : TrainFormEvent
}

/**
 * Add/edit train-ticket form. Prefill paths (pasted SMS/email text, imported
 * PDF/image) land HERE for review with per-field confidence markers — extracted data
 * is NEVER saved without the user seeing it in this form first.
 */
@HiltViewModel
class TrainTicketFormViewModel
    @Inject
    constructor(
        private val repository: TrainRepository,
        private val prefillSource: TrainPrefillSource,
    ) : ViewModel() {
        private val state = MutableStateFlow(TrainFormUiState())
        val uiState: StateFlow<TrainFormUiState> = state.asStateFlow()

        private val eventsFlow = MutableSharedFlow<TrainFormEvent>(extraBufferCapacity = 4)
        val events: SharedFlow<TrainFormEvent> = eventsFlow.asSharedFlow()

        /** Existing rows removed in this edit session — tombstoned on save. */
        private val removedExisting = mutableListOf<TrainPassenger>()

        private var loadedPassengers: List<TrainPassenger> = emptyList()

        /** Resets to a blank add form. */
        fun startBlank() {
            removedExisting.clear()
            loadedPassengers = emptyList()
            state.value = TrainFormUiState()
        }

        /** Loads [ticketId] for editing; falls back to a blank form when missing. */
        fun startEdit(ticketId: String) {
            startBlank()
            viewModelScope.launch {
                val ticket = repository.getTicket(ticketId) ?: return@launch
                val passengers = repository.observePassengers(ticketId).first()
                loadedPassengers = passengers
                state.value =
                    TrainFormUiState(
                        editingTicketId = ticket.id,
                        pnr = ticket.pnr,
                        trainNumber = ticket.trainNumber,
                        trainName = ticket.trainName,
                        journeyDate = ticket.journeyDate,
                        fromStation = ticket.fromStation,
                        toStation = ticket.toStation,
                        travelClass = ticket.travelClass,
                        quota = ticket.quota,
                        passengers =
                            passengers
                                .map { passenger ->
                                    PassengerFormRow(
                                        existingId = passenger.id,
                                        name = passenger.name,
                                        coach = passenger.coach,
                                        seatBerth = passenger.seatBerth,
                                        bookingStatus = passenger.bookingStatus,
                                        currentStatus = passenger.currentStatus,
                                    )
                                }.ifEmpty { listOf(PassengerFormRow()) },
                    )
            }
        }

        /** Starts a blank form and prefills it from pasted/shared IRCTC text. */
        fun startFromText(text: String) {
            startBlank()
            applyExtraction(prefillSource.fromText(text))
        }

        /**
         * Starts a blank form carrying only [pnr] — the incoming share-link path
         * (ADR-020): the recipient fills in what they know, saves, and then runs the
         * captcha-gated PNR check to pull the rest.
         */
        fun startWithPnr(pnr: String) {
            startBlank()
            state.update { it.copy(pnr = pnr.trim(), prefilled = true) }
        }

        /** Starts a blank form and prefills it from an imported PDF/image. */
        fun startFromUri(uri: Uri) {
            startBlank()
            viewModelScope.launch {
                applyExtraction(prefillSource.fromUri(uri))
            }
        }

        /**
         * Applies an extraction to the CURRENT form: only fields the extraction
         * actually recognized are set, each recording its confidence so the UI can
         * mark low-confidence values for review.
         */
        fun applyExtraction(extraction: TrainTicketExtraction) {
            if (extraction.isEmpty) {
                eventsFlow.tryEmit(TrainFormEvent.PrefillEmpty)
                return
            }
            state.update { current ->
                val confidences = mutableMapOf<TrainFormField, ExtractionConfidence>()

                fun take(
                    field: TrainFormField,
                    extracted: ExtractedField,
                    fallback: String,
                ): String {
                    if (!extracted.isPresent) return fallback
                    confidences[field] = extracted.confidence
                    return extracted.value.orEmpty()
                }

                val passengers =
                    extraction.passengers
                        .map { passenger ->
                            PassengerFormRow(
                                name = passenger.name.value.orEmpty(),
                                coach = passenger.coach.value.orEmpty(),
                                seatBerth = passenger.berth.value.orEmpty(),
                                bookingStatus = passenger.bookingStatus.value.orEmpty(),
                                lowConfidence = passenger.name.confidence == ExtractionConfidence.LOW,
                            )
                        }.ifEmpty { current.passengers }

                current.copy(
                    pnr = take(TrainFormField.PNR, extraction.pnr, current.pnr),
                    pnrError = false,
                    trainNumber = take(TrainFormField.TRAIN_NUMBER, extraction.trainNumber, current.trainNumber),
                    trainName = take(TrainFormField.TRAIN_NAME, extraction.trainName, current.trainName),
                    journeyDate = extractedDate(extraction.journeyDate, confidences) ?: current.journeyDate,
                    fromStation = take(TrainFormField.FROM_STATION, extraction.fromStation, current.fromStation),
                    toStation = take(TrainFormField.TO_STATION, extraction.toStation, current.toStation),
                    travelClass = take(TrainFormField.TRAVEL_CLASS, extraction.travelClass, current.travelClass),
                    quota = take(TrainFormField.QUOTA, extraction.quota, current.quota),
                    passengers = passengers,
                    confidences = confidences,
                    prefilled = true,
                )
            }
        }

        fun onPnrChange(value: String) = state.update { it.copy(pnr = value, pnrError = false) }

        fun onTrainNumberChange(value: String) = state.update { it.copy(trainNumber = value) }

        fun onTrainNameChange(value: String) = state.update { it.copy(trainName = value) }

        fun onJourneyDateChange(value: LocalDate?) = state.update { it.copy(journeyDate = value) }

        fun onFromStationChange(value: String) = state.update { it.copy(fromStation = value) }

        fun onToStationChange(value: String) = state.update { it.copy(toStation = value) }

        fun onTravelClassChange(value: String) = state.update { it.copy(travelClass = value) }

        fun onQuotaChange(value: String) = state.update { it.copy(quota = value) }

        fun addPassengerRow() = state.update { it.copy(passengers = it.passengers + PassengerFormRow()) }

        fun removePassengerRow(rowKey: String) {
            state.update { current ->
                val row = current.passengers.firstOrNull { it.rowKey == rowKey } ?: return@update current
                if (row.existingId != null) {
                    loadedPassengers.firstOrNull { it.id == row.existingId }?.let(removedExisting::add)
                }
                current.copy(passengers = current.passengers.filterNot { it.rowKey == rowKey })
            }
        }

        fun updatePassengerRow(
            rowKey: String,
            transform: (PassengerFormRow) -> PassengerFormRow,
        ) {
            state.update { current ->
                current.copy(
                    passengers =
                        current.passengers.map { row ->
                            if (row.rowKey == rowKey) transform(row).copy(lowConfidence = false) else row
                        },
                )
            }
        }

        /** Validates and saves the ticket + passengers; emits [TrainFormEvent.Saved]. */
        fun save() {
            val current = state.value
            if (!isValidPnr(current.pnr)) {
                state.update { it.copy(pnrError = true) }
                return
            }
            if (current.saving) return
            state.update { it.copy(saving = true) }
            viewModelScope.launch {
                val existing = current.editingTicketId?.let { repository.getTicket(it) }
                val ticket =
                    (existing ?: TrainTicket(pnr = current.pnr.trim())).copy(
                        pnr = current.pnr.trim(),
                        trainNumber = current.trainNumber.trim(),
                        trainName = current.trainName.trim(),
                        journeyDate = current.journeyDate,
                        fromStation = current.fromStation.trim(),
                        toStation = current.toStation.trim(),
                        travelClass = current.travelClass.trim(),
                        quota = current.quota.trim(),
                    )
                val saved = repository.save(ticket)
                val rows =
                    current.passengers
                        .filter {
                            it.name.isNotBlank() ||
                                it.coach.isNotBlank() ||
                                it.seatBerth.isNotBlank() ||
                                it.bookingStatus.isNotBlank()
                        }
                val passengers =
                    rows.mapIndexed { index, row ->
                        val base = loadedPassengers.firstOrNull { it.id == row.existingId }
                        (base ?: TrainPassenger(ticketId = saved.id)).copy(
                            ticketId = saved.id,
                            name = row.name.trim(),
                            coach = row.coach.trim(),
                            seatBerth = row.seatBerth.trim(),
                            bookingStatus = row.bookingStatus.trim(),
                            currentStatus = row.currentStatus.trim(),
                            sortOrder = index,
                        )
                    }
                // Rows removed while editing are tombstoned (ADR-002 soft delete):
                // the upsert keeps their UUIDs so backup merges honor the deletion.
                val tombstones = removedExisting.map { it.copy(deletedAt = Instant.now()) }
                repository.savePassengers(passengers + tombstones)
                removedExisting.clear()
                state.update { it.copy(saving = false) }
                val pnrOnlyQuickAdd =
                    existing == null &&
                        ticket.trainNumber.isBlank() &&
                        ticket.trainName.isBlank() &&
                        ticket.journeyDate == null &&
                        ticket.fromStation.isBlank() &&
                        ticket.toStation.isBlank() &&
                        passengers.isEmpty()
                eventsFlow.tryEmit(
                    TrainFormEvent.Saved(saved.id, pnr = saved.pnr, openPnrCheck = pnrOnlyQuickAdd),
                )
            }
        }

        private fun extractedDate(
            field: ExtractedField,
            confidences: MutableMap<TrainFormField, ExtractionConfidence>,
        ): LocalDate? {
            if (!field.isPresent) return null
            val parsed = runCatching { LocalDate.parse(field.value) }.getOrNull() ?: return null
            confidences[TrainFormField.JOURNEY_DATE] = field.confidence
            return parsed
        }
    }
