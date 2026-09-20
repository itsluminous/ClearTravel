package com.itsluminous.cleartravel.feature.flights.form

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsluminous.cleartravel.core.data.repository.FlightRepository
import com.itsluminous.cleartravel.core.model.FlightJourney
import com.itsluminous.cleartravel.feature.flights.checkin.CheckInRuleSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FlightFormViewModel
    @Inject
    constructor(
        private val repository: FlightRepository,
        private val importer: BoardingPassImporter,
        private val checkInRuleSource: CheckInRuleSource,
    ) : ViewModel() {
        private val state = MutableStateFlow(FlightFormState())
        val formState: StateFlow<FlightFormState> = state

        private val busy = MutableStateFlow(false)

        /** True while a boarding-pass prefill or a save is running. */
        val isBusy: StateFlow<Boolean> = busy

        /** Blank add form. */
        fun startBlank() {
            state.value = FlightFormState()
        }

        /** Edit form for an existing journey. */
        fun startEdit(flightId: String) {
            viewModelScope.launch {
                repository.getFlight(flightId)?.let { state.value = FlightFormState.fromJourney(it) }
            }
        }

        /**
         * Boarding-pass import: run the barcode-first/OCR-fallback pipeline on the
         * picked file and prefill the form with confidence markers. The user reviews —
         * never saved blind (ADR-009). An unrecognized file degrades to a blank form
         * with the file still attached for storage.
         */
        fun startFromBoardingPass(uriString: String) {
            state.value = FlightFormState(pendingPassUri = uriString)
            busy.value = true
            viewModelScope.launch {
                val extraction = importer.prefill(uriString)
                state.value = FlightFormState.fromExtraction(extraction, passUri = uriString)
                busy.value = false
            }
        }

        fun update(transform: (FlightFormState) -> FlightFormState) {
            state.value = transform(state.value).copy(errors = emptySet())
        }

        /**
         * Validates and persists. Invalid input surfaces via [FlightFormState.errors]
         * and calls back nothing; success invokes [onSaved] with the journey id so the
         * caller can close the form or chain straight into the status-check flow
         * ("Save & fetch details").
         */
        fun save(onSaved: (flightId: String) -> Unit) {
            val current = state.value
            val errors = FlightFormState.validate(current)
            if (errors.isNotEmpty()) {
                state.value = current.copy(errors = errors)
                return
            }
            busy.value = true
            viewModelScope.launch {
                val journey = buildJourney(current)
                val passPath =
                    current.pendingPassUri?.let { importer.store(it, journey.id) }
                        ?: journey.boardingPassPath
                repository.save(journey.copy(boardingPassPath = passPath))
                busy.value = false
                onSaved(journey.id)
            }
        }

        /** Merge-on-edit: user-editable fields overwrite; provider fields survive. */
        private suspend fun buildJourney(current: FlightFormState): FlightJourney {
            val checkInUrl = checkInRuleSource.load().checkInUrl(current.airlineIata.trim().uppercase())
            val draft = current.toJourney(checkInUrl = checkInUrl)
            val existing = current.editingId?.let { repository.getFlight(it) } ?: return draft
            return existing.copy(
                airlineIata = draft.airlineIata,
                flightNumber = draft.flightNumber,
                date = draft.date,
                pnrBookingRef = draft.pnrBookingRef,
                seat = draft.seat,
                cabinClass = draft.cabinClass,
                depAirport = draft.depAirport,
                arrAirport = draft.arrAirport,
                schedDep = draft.schedDep ?: existing.schedDep,
                schedArr = draft.schedArr ?: existing.schedArr,
                checkInUrl = draft.checkInUrl ?: existing.checkInUrl,
            )
        }
    }
