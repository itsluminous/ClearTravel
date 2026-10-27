package com.itsluminous.cleartravel.feature.flights.form

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsluminous.cleartravel.core.data.repository.FlightRepository
import com.itsluminous.cleartravel.core.data.share.FlightSharePayload
import com.itsluminous.cleartravel.core.model.FlightJourney
import com.itsluminous.cleartravel.core.ocr.model.BoardingPassExtraction
import com.itsluminous.cleartravel.core.ocr.model.BookingConfirmationExtraction
import com.itsluminous.cleartravel.feature.flights.checkin.CheckInRuleSource
import com.itsluminous.cleartravel.feature.flights.text.FlightTextParser
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Which file an ADR-045 multi-flight import came from — decides how the file is stored per flight. */
enum class FlightImportKind {
    /** Encrypted copy per flight into `boarding_passes/<flightId>.<ext>` (frozen `boardingPassPath`). */
    BOARDING_PASS,

    /** One FLIGHT attachment row (own encrypted copy) per flight (ADR-017). */
    BOOKING_CONFIRMATION,
}

/** One segment on the review sheet (ADR-045): its prefilled form and whether the user kept it. */
data class FlightImportReviewItem(
    val form: FlightFormState,
    val selected: Boolean = true,
) {
    /** Can be written straight away (airline + number + date + both airports, all valid). */
    val isComplete: Boolean get() = form.isCompleteForDirectSave
}

/**
 * The ADR-045 review step: a file yielded MORE than one flight segment, so the user
 * confirms which to add before anything is written. All selected and every item
 * complete → one tap saves them all; any incomplete item → the selected segments are
 * walked through the form one at a time.
 */
data class FlightImportReview(
    val kind: FlightImportKind,
    val uri: String,
    val items: List<FlightImportReviewItem>,
) {
    val selectedCount: Int get() = items.count { it.selected }
}

/** How a multi-flight import ended (ADR-045): what was written and what ADR-025 refused. */
data class FlightBatchOutcome(
    val savedIds: List<String>,
    /** Existing journeys that already carried a selected segment's identity — nothing written for them. */
    val skippedExistingIds: List<String>,
) {
    val addedCount: Int get() = savedIds.size
    val skippedCount: Int get() = skippedExistingIds.size

    /** The journey a "View" action should open: the first added, else the first existing. */
    val focusFlightId: String? get() = savedIds.firstOrNull() ?: skippedExistingIds.firstOrNull()
}

/** One-shot outcomes of the flight form that hosts react to (saves stay callback-based). */
sealed interface FlightFormEvent {
    /**
     * Save refused: a live journey (archived included) already flies this airline +
     * flight number on this date (ADR-025). Nothing was written; the host shows a
     * notice and offers to open [existingFlightId] instead of creating a second card
     * for the same flight.
     */
    data class DuplicateFlight(
        val existingFlightId: String,
    ) : FlightFormEvent

    /**
     * Pasted/shared text held no flight at all (ADR-042): the form opened blank. The
     * host explains it in a snackbar — fields that merely went unrecognised stay empty
     * silently, like every other prefill path.
     */
    data object PrefillEmpty : FlightFormEvent

    /**
     * A multi-flight import finished (ADR-045) — directly from the review sheet or at
     * the end of the walk-through. The host leaves the form and reports
     * "Added N, skipped M already saved".
     */
    data class BatchImported(
        val outcome: FlightBatchOutcome,
    ) : FlightFormEvent
}

@HiltViewModel
class FlightFormViewModel
    @Inject
    constructor(
        private val repository: FlightRepository,
        private val importer: BoardingPassImporter,
        private val bookingImporter: BookingConfirmationImporter,
        private val checkInRuleSource: CheckInRuleSource,
        private val textParser: FlightTextParser,
    ) : ViewModel() {
        private val state = MutableStateFlow(FlightFormState())
        val formState: StateFlow<FlightFormState> = state

        private val busy = MutableStateFlow(false)

        /** True while a boarding-pass prefill or a save is running. */
        val isBusy: StateFlow<Boolean> = busy

        private val review = MutableStateFlow<FlightImportReview?>(null)

        /** The ADR-045 review step, shown over the form while a multi-flight file awaits confirmation. */
        val importReview: StateFlow<FlightImportReview?> = review

        private val eventsFlow = MutableSharedFlow<FlightFormEvent>(extraBufferCapacity = 4)
        val events: SharedFlow<FlightFormEvent> = eventsFlow.asSharedFlow()

        /** Walk-through state (ADR-045): the forms still to show, plus what has happened so far. */
        private var walkQueue: List<FlightFormState> = emptyList()
        private var walkKind: FlightImportKind? = null
        private var walkUri: String? = null
        private val walkSaved = mutableListOf<String>()
        private val walkSkipped = mutableListOf<String>()

        /** Blank add form. */
        fun startBlank() {
            clearWalkthrough()
            state.value = FlightFormState()
        }

        /** Edit form for an existing journey. */
        fun startEdit(flightId: String) {
            clearWalkthrough()
            viewModelScope.launch {
                repository.getFlight(flightId)?.let { state.value = FlightFormState.fromJourney(it) }
            }
        }

        /**
         * Boarding-pass import: run the barcode-first/OCR-fallback pipeline on the
         * picked file. ONE segment prefills the form with confidence markers exactly as
         * before (the user reviews — never saved blind, ADR-009; an unrecognized file
         * degrades to a blank form with the file still attached for storage). MORE than
         * one segment (an `M2` barcode, a pass per page, two passes on one page) opens
         * the ADR-045 review step instead.
         */
        fun startFromBoardingPass(uriString: String) {
            clearWalkthrough()
            state.value = FlightFormState(pendingPassUri = uriString)
            busy.value = true
            viewModelScope.launch {
                val extraction = importer.prefill(uriString)
                if (extraction.flights.size > 1) {
                    review.value = reviewOf(FlightImportKind.BOARDING_PASS, uriString, extraction)
                } else {
                    state.value = FlightFormState.fromExtraction(extraction, passUri = uriString)
                }
                busy.value = false
            }
        }

        /**
         * Booking-confirmation import (third add path, ADR-017): barcode-first/OCR
         * pipeline prefill with confidence markers. ONE flight → the form, as before;
         * a confirmation describing SEVERAL flights (return trip, multi-city) → the
         * ADR-045 review step. The file is stored as a FLIGHT attachment of every
         * journey it produces, on save.
         */
        fun startFromBookingConfirmation(uriString: String) {
            clearWalkthrough()
            state.value = FlightFormState(pendingBookingUri = uriString)
            busy.value = true
            viewModelScope.launch {
                val extraction = bookingImporter.prefill(uriString)
                if (extraction.flights.size > 1) {
                    review.value = reviewOf(FlightImportKind.BOOKING_CONFIRMATION, uriString, extraction)
                } else {
                    state.value = FlightFormState.fromBookingExtraction(extraction, bookingUri = uriString)
                }
                busy.value = false
            }
        }

        private fun reviewOf(
            kind: FlightImportKind,
            uri: String,
            extraction: BoardingPassExtraction,
        ): FlightImportReview =
            FlightImportReview(
                kind = kind,
                uri = uri,
                items =
                    extraction.flights.map {
                        FlightImportReviewItem(
                            FlightFormState.fromBoardingPassFlight(it, extraction.source, uri),
                        )
                    },
            )

        private fun reviewOf(
            kind: FlightImportKind,
            uri: String,
            extraction: BookingConfirmationExtraction,
        ): FlightImportReview =
            FlightImportReview(
                kind = kind,
                uri = uri,
                items = extraction.flights.map { FlightImportReviewItem(FlightFormState.fromBookingFlight(it, extraction.source, uri)) },
            )

        /** Review step: keep or drop segment [index]. */
        fun toggleReviewItem(index: Int) {
            review.value =
                review.value?.let { current ->
                    current.copy(
                        items = current.items.mapIndexed { i, item -> if (i == index) item.copy(selected = !item.selected) else item },
                    )
                }
        }

        /** Review step dismissed: nothing was written; the host closes the form. */
        fun dismissReview() {
            review.value = null
        }

        /**
         * Review step confirmed (ADR-045). Every selected segment complete → all are
         * written now, each through the ADR-025 duplicate guard (a refused one is
         * reported as skipped, the rest still save) with the file stored for EVERY
         * flight; the result arrives as [FlightFormEvent.BatchImported]. Otherwise the
         * selected segments are walked through the form one at a time — the first one
         * is shown now, [save] advances — and the same event closes the walk.
         */
        fun confirmReview() {
            val current = review.value ?: return
            val chosen = current.items.filter { it.selected }.map { it.form }
            if (chosen.isEmpty()) return
            review.value = null
            if (chosen.all { it.isCompleteForDirectSave }) {
                busy.value = true
                viewModelScope.launch {
                    val saved = mutableListOf<String>()
                    val skipped = mutableListOf<String>()
                    for (form in chosen) writeImported(form, current.kind, current.uri, saved, skipped)
                    busy.value = false
                    eventsFlow.tryEmit(FlightFormEvent.BatchImported(FlightBatchOutcome(saved, skipped)))
                }
            } else {
                walkKind = current.kind
                walkUri = current.uri
                walkSaved.clear()
                walkSkipped.clear()
                walkQueue = chosen.drop(1)
                state.value = chosen.first().copy(walkthrough = WalkthroughProgress(index = 1, total = chosen.size))
            }
        }

        /**
         * Shared-link import (ADR-039): the payload's add data prefills the form; the
         * user adds their own booking reference/seat and saves through the normal path
         * (including the ADR-025 duplicate guard). Nothing is ever saved blind.
         */
        fun startFromShared(payload: FlightSharePayload) {
            clearWalkthrough()
            state.value = FlightFormState.fromSharePayload(payload)
        }

        /**
         * SMS/email text import (ADR-042): pure parsing against the airline rules
         * asset, prefill with confidence markers; the user reviews — never saved blind.
         * Text with no flight in it opens a blank form and emits
         * [FlightFormEvent.PrefillEmpty].
         */
        fun startFromText(text: String) {
            clearWalkthrough()
            val extraction = textParser.parse(text)
            if (extraction.isEmpty) {
                state.value = FlightFormState()
                eventsFlow.tryEmit(FlightFormEvent.PrefillEmpty)
            } else {
                state.value = FlightFormState.fromTextExtraction(extraction)
            }
        }

        fun update(transform: (FlightFormState) -> FlightFormState) {
            state.value = transform(state.value).copy(errors = emptySet())
        }

        /**
         * Validates and persists. Invalid input surfaces via [FlightFormState.errors]
         * and calls back nothing; success invokes [onSaved] with the journey id so the
         * caller can close the form or chain straight into the status-check flow
         * ("Save & fetch details"). When another live journey already has this
         * airline + flight number + date, NOTHING is written and
         * [FlightFormEvent.DuplicateFlight] is emitted instead (ADR-025) — editing a
         * journey never trips on its own identity, only re-pointing it at ANOTHER
         * journey's does. Every add path (manual, boarding pass, booking confirmation, pasted text,
         * share-sheet intake) funnels through here, so the guard covers all of them.
         *
         * ADR-045 walk-through: while [FlightFormState.walkthrough] is set, a save (or a
         * refused duplicate, counted as skipped) ADVANCES to the next segment's form
         * instead of calling [onSaved]; the last one emits [FlightFormEvent.BatchImported].
         */
        fun save(onSaved: (flightId: String) -> Unit) {
            val current = state.value
            val errors = FlightFormState.validate(current)
            if (errors.isNotEmpty()) {
                state.value = current.copy(errors = errors)
                return
            }
            // Validation guarantees a parseable date; a null here cannot happen.
            val date = FlightFormState.parseDate(current.dateText) ?: return
            busy.value = true
            viewModelScope.launch {
                if (current.walkthrough != null) {
                    val kind = walkKind
                    val uri = walkUri
                    if (kind != null && uri != null) {
                        writeImported(current.copy(walkthrough = null), kind, uri, walkSaved, walkSkipped)
                    }
                    advanceWalkthrough(current.walkthrough)
                    busy.value = false
                    return@launch
                }
                val duplicate =
                    repository
                        .findByFlight(current.airlineIata, current.flightNumber, date)
                        ?.takeIf { it.id != current.editingId }
                if (duplicate != null) {
                    busy.value = false
                    eventsFlow.tryEmit(FlightFormEvent.DuplicateFlight(existingFlightId = duplicate.id))
                    return@launch
                }
                val journey = buildJourney(current)
                val passPath =
                    current.pendingPassUri?.let { importer.store(it, journey.id) }
                        ?: journey.boardingPassPath
                repository.save(journey.copy(boardingPassPath = passPath))
                // Booking confirmations live as FLIGHT attachment rows (ADR-017), so
                // Drive upload + backup bundling apply automatically; a failed copy
                // simply saves the flight without the document.
                current.pendingBookingUri?.let { bookingImporter.attach(it, journey.id) }
                busy.value = false
                onSaved(journey.id)
            }
        }

        /**
         * Writes ONE imported segment: ADR-025 guard → journey row → the file stored for
         * THIS flight (its own encrypted copy, so deleting one flight never touches the
         * other's). A refused duplicate lands in [skipped], a write in [saved].
         */
        private suspend fun writeImported(
            form: FlightFormState,
            kind: FlightImportKind,
            uri: String,
            saved: MutableList<String>,
            skipped: MutableList<String>,
        ) {
            val date = FlightFormState.parseDate(form.dateText) ?: return
            val duplicate = repository.findByFlight(form.airlineIata, form.flightNumber, date)
            if (duplicate != null) {
                skipped += duplicate.id
                return
            }
            val journey = buildJourney(form.copy(editingId = null))
            when (kind) {
                FlightImportKind.BOARDING_PASS -> {
                    val passPath = importer.store(uri, journey.id)
                    repository.save(journey.copy(boardingPassPath = passPath))
                }
                FlightImportKind.BOOKING_CONFIRMATION -> {
                    repository.save(journey)
                    bookingImporter.attach(uri, journey.id)
                }
            }
            saved += journey.id
        }

        private fun advanceWalkthrough(progress: WalkthroughProgress) {
            val next = walkQueue.firstOrNull()
            if (next == null) {
                val outcome = FlightBatchOutcome(walkSaved.toList(), walkSkipped.toList())
                clearWalkthrough()
                state.value = FlightFormState()
                eventsFlow.tryEmit(FlightFormEvent.BatchImported(outcome))
            } else {
                walkQueue = walkQueue.drop(1)
                state.value = next.copy(walkthrough = WalkthroughProgress(index = progress.index + 1, total = progress.total))
            }
        }

        private fun clearWalkthrough() {
            walkQueue = emptyList()
            walkKind = null
            walkUri = null
            walkSaved.clear()
            walkSkipped.clear()
            review.value = null
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
                depTerminal = draft.depTerminal.ifBlank { existing.depTerminal },
                arrTerminal = draft.arrTerminal.ifBlank { existing.arrTerminal },
            )
        }
    }
