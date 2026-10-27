package com.itsluminous.cleartravel.feature.flights.form

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsluminous.cleartravel.core.data.repository.FlightPassengerMatching
import com.itsluminous.cleartravel.core.data.repository.FlightRepository
import com.itsluminous.cleartravel.core.data.share.FlightSharePayload
import com.itsluminous.cleartravel.core.model.FlightJourney
import com.itsluminous.cleartravel.core.model.FlightPassenger
import com.itsluminous.cleartravel.core.model.FlightPassengerIds
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

/** A passenger added to an EXISTING journey by an import or save (ADR-046 decision 5). */
data class AddedPassenger(
    val flightId: String,
    /** Display name, or the seat when the pass carried no name. */
    val label: String,
    /** `IX 2019` — the journey as the notice names it (carried so the notice never waits on the list). */
    val flightLabel: String,
)

/**
 * How an import or a save that met an existing journey ended (ADR-045/046): journeys
 * written, passengers added to existing journeys, and identities refused as already
 * saved.
 */
data class FlightBatchOutcome(
    val savedIds: List<String>,
    /** Existing journeys that already carried a selected segment's identity AND its passenger — nothing written for them. */
    val skippedExistingIds: List<String>,
    /** Passengers added to journeys that already existed (ADR-046). */
    val addedPassengers: List<AddedPassenger> = emptyList(),
) {
    val addedCount: Int get() = savedIds.size
    val skippedCount: Int get() = skippedExistingIds.size
    val passengerCount: Int get() = addedPassengers.size

    /** The journey a "View" action should open: the first added, else the first grown, else the first existing. */
    val focusFlightId: String?
        get() = savedIds.firstOrNull() ?: addedPassengers.firstOrNull()?.flightId ?: skippedExistingIds.firstOrNull()

    /** True when the whole outcome is ONE passenger joining ONE existing journey — the "Added X to IX 2019" notice. */
    val isSinglePassengerAdd: Boolean get() = savedIds.isEmpty() && skippedExistingIds.isEmpty() && addedPassengers.size == 1
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
     * the end of the walk-through — OR a single save met an existing journey and added
     * its passenger to it (ADR-046). The host leaves the form and reports "Added N,
     * skipped M already saved" / "Added Bandana Kumari to IX 2019".
     */
    data class BatchImported(
        val outcome: FlightBatchOutcome,
    ) : FlightFormEvent
}

/** What became of ONE flight written through the ADR-025/046 guard. */
private sealed interface WriteResult {
    data class Saved(
        val flightId: String,
    ) : WriteResult

    data class PassengerAdded(
        val added: List<AddedPassenger>,
    ) : WriteResult

    data class AlreadySaved(
        val existingFlightId: String,
    ) : WriteResult
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
        private val walkPassengers = mutableListOf<AddedPassenger>()

        /** Blank add form. */
        fun startBlank() {
            clearWalkthrough()
            state.value = FlightFormState()
        }

        /** Edit form for an existing journey and its passengers (ADR-046). */
        fun startEdit(flightId: String) {
            clearWalkthrough()
            viewModelScope.launch {
                repository.getFlight(flightId)?.let { flight ->
                    state.value = FlightFormState.fromJourney(flight, repository.getPassengers(flightId))
                }
            }
        }

        /** Passengers section (ADR-046): append an empty row. */
        fun addPassengerRow() {
            update { it.copy(passengers = it.passengers + PassengerFormRow()) }
        }

        /** Passengers section: drop the row; the last row is cleared instead of removed so one row always shows. */
        fun removePassengerRow(rowKey: String) {
            update { current ->
                val remaining = current.passengers.filterNot { it.rowKey == rowKey }
                current.copy(passengers = remaining.ifEmpty { listOf(PassengerFormRow()) })
            }
        }

        fun updatePassengerRow(
            rowKey: String,
            transform: (PassengerFormRow) -> PassengerFormRow,
        ) {
            update { current -> current.copy(passengers = current.passengers.map { if (it.rowKey == rowKey) transform(it) else it }) }
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
                    val passengers = mutableListOf<AddedPassenger>()
                    for (form in chosen) writeImported(form, current.kind, current.uri, saved, skipped, passengers)
                    busy.value = false
                    eventsFlow.tryEmit(FlightFormEvent.BatchImported(FlightBatchOutcome(saved, skipped, passengers)))
                }
            } else {
                walkKind = current.kind
                walkUri = current.uri
                walkSaved.clear()
                walkSkipped.clear()
                walkPassengers.clear()
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
         * the form's passengers are matched against that journey's (ADR-046): a NEW
         * person is added to it and [FlightFormEvent.BatchImported] reports "Added X to
         * IX 2019"; a person already on board — or a form naming nobody — emits
         * [FlightFormEvent.DuplicateFlight] instead (ADR-025) and writes nothing. Editing
         * a journey never trips on its own identity, only re-pointing it at ANOTHER
         * journey's does. Every add path (manual, boarding pass, booking confirmation,
         * pasted text, share-sheet intake) funnels through here, so the guard covers all
         * of them.
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
                        writeImported(current.copy(walkthrough = null), kind, uri, walkSaved, walkSkipped, walkPassengers)
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
                    // ADR-046: the flight exists — maybe the PERSON does not. Re-pointing an
                    // EDITED journey onto another's identity stays a plain refusal: merging
                    // one journey's passengers into another is never what an edit means.
                    val result =
                        if (current.editingId != null) WriteResult.AlreadySaved(duplicate.id) else mergeIntoExisting(duplicate, current)
                    busy.value = false
                    when (result) {
                        is WriteResult.PassengerAdded ->
                            eventsFlow.tryEmit(
                                FlightFormEvent.BatchImported(FlightBatchOutcome(emptyList(), emptyList(), result.added)),
                            )
                        is WriteResult.AlreadySaved -> eventsFlow.tryEmit(FlightFormEvent.DuplicateFlight(existingFlightId = duplicate.id))
                        is WriteResult.Saved -> onSaved(result.flightId)
                    }
                    return@launch
                }
                val journey = buildJourney(current)
                repository.save(journey)
                writePassengers(journey.id, current, current.pendingPassUri)
                // Booking confirmations live as FLIGHT attachment rows (ADR-017), so
                // Drive upload + backup bundling apply automatically; a failed copy
                // simply saves the flight without the document.
                current.pendingBookingUri?.let { bookingImporter.attach(it, journey.id) }
                busy.value = false
                onSaved(journey.id)
            }
        }

        /**
         * Writes the form's passenger rows for [flightId] (ADR-046): the pending
         * boarding pass — when any — is stored as ROW 1's pass under the passenger's id;
         * stored passes of edited rows survive; removed rows are tombstoned.
         */
        private suspend fun writePassengers(
            flightId: String,
            form: FlightFormState,
            passUri: String?,
        ) {
            val rows = form.toPassengers(flightId).toMutableList()
            if (passUri != null) {
                val first = rows.firstOrNull() ?: PassengerFormRow().toPassenger(flightId, 0).also { rows += it }
                importer.store(passUri, first.id)?.let { rows[0] = first.copy(boardingPassPath = it) }
            }
            repository.replacePassengers(flightId, rows)
        }

        /**
         * ADR-046 decision 5: the form's identity matches [existing]. Every row with
         * passenger info that is NOT already on the flight is added (its pending pass
         * stored under the new passenger's id, a pending booking attached to the flight);
         * a row that IS on it fills the matched passenger's blanks (name, locator, a
         * missing pass). No row with info at all → the ADR-025 refusal as before.
         */
        private suspend fun mergeIntoExisting(
            existing: FlightJourney,
            form: FlightFormState,
        ): WriteResult {
            val incoming = form.toPassengers(existing.id).filter(FlightPassengerMatching::hasPassengerInfo)
            if (incoming.isEmpty()) return WriteResult.AlreadySaved(existing.id)
            val onBoard = repository.getPassengers(existing.id).toMutableList()
            if (onBoard.isEmpty() && (existing.seat.isNotBlank() || existing.boardingPassPath != null)) {
                // A journey written before passengers existed (or straight through
                // `save`): its mirror seat/pass IS passenger #1 — materialise the same row
                // the schema migration would have, so the match below sees them.
                onBoard +=
                    repository.savePassengers(
                        existing.id,
                        listOf(
                            FlightPassenger(
                                id = FlightPassengerIds.legacy(existing.id),
                                flightId = existing.id,
                                seat = existing.seat.trim(),
                                boardingPassPath = existing.boardingPassPath,
                            ),
                        ),
                    )
            }
            val added = mutableListOf<AddedPassenger>()
            incoming.forEachIndexed { index, candidate ->
                val passUri = form.pendingPassUri?.takeIf { index == 0 }
                val match = FlightPassengerMatching.findMatch(onBoard, candidate)
                if (match == null) {
                    val passPath = passUri?.let { importer.store(it, candidate.id) }
                    val saved =
                        repository.savePassengers(
                            existing.id,
                            listOf(candidate.copy(boardingPassPath = passPath, sortOrder = onBoard.size)),
                        )
                    onBoard += saved
                    added +=
                        AddedPassenger(
                            flightId = existing.id,
                            label = candidate.name.ifBlank { candidate.seat },
                            flightLabel = "${existing.airlineIata} ${existing.flightNumber}".trim(),
                        )
                } else {
                    var filled = FlightPassengerMatching.fillBlanks(match, candidate)
                    if (filled.boardingPassPath == null && passUri != null) {
                        filled = filled.copy(boardingPassPath = importer.store(passUri, match.id))
                    }
                    if (filled != match) {
                        repository.savePassengers(existing.id, listOf(filled))
                        onBoard[onBoard.indexOf(match)] = filled
                    }
                }
            }
            if (added.isEmpty()) return WriteResult.AlreadySaved(existing.id)
            form.pendingBookingUri?.let { bookingImporter.attach(it, existing.id) }
            return WriteResult.PassengerAdded(added)
        }

        /**
         * Writes ONE imported segment: ADR-025/046 guard → journey row + passenger row →
         * the file stored for THIS flight's passenger (its own encrypted copy, so deleting
         * one never touches another's). An existing journey gains the segment's passenger
         * when they are new to it ([passengers]), is skipped when they are already on it
         * ([skipped]); a new journey lands in [saved].
         */
        private suspend fun writeImported(
            form: FlightFormState,
            kind: FlightImportKind,
            uri: String,
            saved: MutableList<String>,
            skipped: MutableList<String>,
            passengers: MutableList<AddedPassenger>,
        ) {
            val date = FlightFormState.parseDate(form.dateText) ?: return
            val staged =
                when (kind) {
                    FlightImportKind.BOARDING_PASS -> form.copy(editingId = null, pendingPassUri = uri, pendingBookingUri = null)
                    FlightImportKind.BOOKING_CONFIRMATION -> form.copy(editingId = null, pendingPassUri = null, pendingBookingUri = uri)
                }
            val duplicate = repository.findByFlight(form.airlineIata, form.flightNumber, date)
            if (duplicate != null) {
                when (val result = mergeIntoExisting(duplicate, staged)) {
                    is WriteResult.PassengerAdded -> passengers += result.added
                    is WriteResult.AlreadySaved -> skipped += duplicate.id
                    is WriteResult.Saved -> saved += result.flightId
                }
                return
            }
            val journey = buildJourney(staged)
            repository.save(journey)
            writePassengers(journey.id, staged, staged.pendingPassUri)
            staged.pendingBookingUri?.let { bookingImporter.attach(it, journey.id) }
            saved += journey.id
        }

        private fun advanceWalkthrough(progress: WalkthroughProgress) {
            val next = walkQueue.firstOrNull()
            if (next == null) {
                val outcome = FlightBatchOutcome(walkSaved.toList(), walkSkipped.toList(), walkPassengers.toList())
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
            walkPassengers.clear()
            review.value = null
        }

        /**
         * Merge-on-edit: user-editable fields overwrite; provider fields survive; the
         * mirror columns (`seat`, `boardingPassPath`) are the repository's to derive.
         */
        private suspend fun buildJourney(current: FlightFormState): FlightJourney {
            val checkInUrl = checkInRuleSource.load().checkInUrl(current.airlineIata.trim().uppercase())
            val draft = current.toJourney(checkInUrl = checkInUrl)
            val existing = current.editingId?.let { repository.getFlight(it) } ?: return draft
            return existing.copy(
                airlineIata = draft.airlineIata,
                flightNumber = draft.flightNumber,
                date = draft.date,
                pnrBookingRef = draft.pnrBookingRef,
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
