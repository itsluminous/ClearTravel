package com.itsluminous.cleartravel.feature.flights.form

import com.itsluminous.cleartravel.core.data.share.FlightSharePayload
import com.itsluminous.cleartravel.core.data.share.SharePayloadMappers
import com.itsluminous.cleartravel.core.model.EntityIds
import com.itsluminous.cleartravel.core.model.FlightJourney
import com.itsluminous.cleartravel.core.ocr.ExtractionConfidence
import com.itsluminous.cleartravel.core.ocr.model.BoardingPassExtraction
import com.itsluminous.cleartravel.core.ocr.model.BoardingPassSource
import com.itsluminous.cleartravel.core.ocr.model.BookingConfirmationExtraction
import com.itsluminous.cleartravel.core.ocr.model.BookingConfirmationSource
import com.itsluminous.cleartravel.core.ocr.model.ExtractedField
import com.itsluminous.cleartravel.core.ocr.model.ExtractedFlight
import com.itsluminous.cleartravel.feature.flights.text.FlightTextExtraction
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Which form fields can carry an OCR/barcode confidence marker. */
enum class FlightField {
    AIRLINE,
    FLIGHT_NUMBER,
    DATE,
    PNR,
    SEAT,
    CABIN,
    DEP_AIRPORT,
    ARR_AIRPORT,
}

/** Validation failures, keyed for per-field supporting text. */
enum class FlightFormError {
    AIRLINE_INVALID,
    FLIGHT_NUMBER_INVALID,
    DATE_INVALID,
    DEP_TIME_INVALID,
    ARR_TIME_INVALID,
}

/**
 * Editable form state. All values are raw user-typed strings; [toJourney] converts.
 * [confidences] carries the OCR/barcode per-field markers (ADR-009 review-first
 * discipline: prefills are NEVER saved blind); [prefillSource] drives the
 * barcode-vs-OCR indicator.
 */
data class FlightFormState(
    val editingId: String? = null,
    val airlineIata: String = "",
    val flightNumber: String = "",
    val dateText: String = "",
    val pnr: String = "",
    val seat: String = "",
    val cabinClass: String = "",
    val depAirport: String = "",
    val arrAirport: String = "",
    val depTimeText: String = "",
    val arrTimeText: String = "",
    val confidences: Map<FlightField, ExtractionConfidence> = emptyMap(),
    val prefillSource: BoardingPassSource = BoardingPassSource.NONE,
    val pendingPassUri: String? = null,
    val existingPassPath: String? = null,
    /** Picked booking-confirmation file, stored as a FLIGHT attachment on save. */
    val pendingBookingUri: String? = null,
    /** How the booking-confirmation prefill was obtained (barcode/OCR/nothing). */
    val bookingSource: BookingConfirmationSource = BookingConfirmationSource.NONE,
    /** True when the confirmation described further segments (return trip). */
    val returnLegHint: Boolean = false,
    /** True when prefilled from a shared flight link (ADR-039) — drives the banner. */
    val fromSharedLink: Boolean = false,
    /** True when prefilled from pasted/shared SMS or email text (ADR-042) — drives the banner. */
    val fromText: Boolean = false,
    /**
     * Terminals read from pasted text (ADR-042). Not editable in the form (they are
     * provider fields the status check overwrites), but shown in the prefill banner so
     * nothing reaches the journey unseen; blank = leave whatever the journey has.
     */
    val depTerminal: String = "",
    val arrTerminal: String = "",
    val errors: Set<FlightFormError> = emptySet(),
) {
    val isEdit: Boolean get() = editingId != null

    companion object {
        val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
        private val TIME_FORMAT = DateTimeFormatter.ofPattern("H:mm")
        private val AIRLINE_PATTERN = Regex("[A-Z0-9]{2}")
        private val FLIGHT_NUMBER_PATTERN = Regex("[0-9]{1,4}[A-Z]?")
        private val AIRPORT_PATTERN = Regex("[A-Za-z]{3}")

        fun fromJourney(journey: FlightJourney): FlightFormState {
            val zone = ZoneId.systemDefault()
            return FlightFormState(
                editingId = journey.id,
                airlineIata = journey.airlineIata,
                flightNumber = journey.flightNumber,
                dateText = journey.date?.format(DATE_FORMAT).orEmpty(),
                pnr = journey.pnrBookingRef,
                seat = journey.seat,
                cabinClass = journey.cabinClass,
                depAirport = journey.depAirport,
                arrAirport = journey.arrAirport,
                depTimeText =
                    journey.schedDep
                        ?.atZone(zone)
                        ?.toLocalTime()
                        ?.format(TIME_FORMAT)
                        .orEmpty(),
                arrTimeText =
                    journey.schedArr
                        ?.atZone(zone)
                        ?.toLocalTime()
                        ?.format(TIME_FORMAT)
                        .orEmpty(),
                existingPassPath = journey.boardingPassPath,
            )
        }

        /**
         * Prefill from a shared flight link (ADR-039): identity, route and scheduled
         * times in the DEVICE zone; personal fields (PNR, seat, cabin) are left blank
         * for the recipient. No confidence markers — the data is exact, not scanned.
         */
        fun fromSharePayload(
            payload: FlightSharePayload,
            zone: ZoneId = ZoneId.systemDefault(),
        ): FlightFormState {
            fun timeText(epochSecond: Long?): String =
                SharePayloadMappers
                    .toInstant(epochSecond)
                    ?.atZone(zone)
                    ?.toLocalTime()
                    ?.format(TIME_FORMAT)
                    .orEmpty()

            return FlightFormState(
                airlineIata = payload.airlineIata.trim().uppercase(),
                flightNumber = payload.flightNumber.trim().uppercase(),
                dateText = SharePayloadMappers.parseDate(payload.date)?.format(DATE_FORMAT).orEmpty(),
                depAirport = payload.depAirport.trim().uppercase(),
                arrAirport = payload.arrAirport.trim().uppercase(),
                depTimeText = timeText(payload.schedDep),
                arrTimeText = timeText(payload.schedArr),
                fromSharedLink = true,
            )
        }

        /**
         * Prefill from ONE extracted flight segment (ADR-045) — confidence markers per
         * field; times arrive as `HH:mm` and are re-formatted the way the form shows
         * them; terminals are not form fields (the status check owns them) but travel on
         * the state so they reach the journey and the banner; the passenger name is
         * dropped (the form has no such field).
         */
        fun fromFlight(flight: ExtractedFlight): FlightFormState {
            fun conf(field: ExtractedField) = field.confidence

            fun timeText(field: ExtractedField): String =
                field.value
                    ?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
                    ?.format(TIME_FORMAT)
                    .orEmpty()

            return FlightFormState(
                airlineIata =
                    flight.carrier.value
                        .orEmpty()
                        .uppercase(),
                flightNumber = flight.flightNumber.value.orEmpty(),
                dateText = flight.flightDate.value.orEmpty(),
                pnr = flight.pnr.value.orEmpty(),
                seat = flight.seat.value.orEmpty(),
                cabinClass = flight.cabinClass.value.orEmpty(),
                depAirport =
                    flight.fromAirport.value
                        .orEmpty()
                        .uppercase(),
                arrAirport =
                    flight.toAirport.value
                        .orEmpty()
                        .uppercase(),
                depTimeText = timeText(flight.depTime),
                arrTimeText = timeText(flight.arrTime),
                depTerminal = flight.depTerminal.value.orEmpty(),
                arrTerminal = flight.arrTerminal.value.orEmpty(),
                confidences =
                    mapOf(
                        FlightField.AIRLINE to conf(flight.carrier),
                        FlightField.FLIGHT_NUMBER to conf(flight.flightNumber),
                        FlightField.DATE to conf(flight.flightDate),
                        FlightField.PNR to conf(flight.pnr),
                        FlightField.SEAT to conf(flight.seat),
                        FlightField.CABIN to conf(flight.cabinClass),
                        FlightField.DEP_AIRPORT to conf(flight.fromAirport),
                        FlightField.ARR_AIRPORT to conf(flight.toAirport),
                    ).filterValues { it != ExtractionConfidence.NONE },
            )
        }

        /**
         * Prefill from a boarding-pass extraction's FIRST segment (the single-pass path;
         * multi-pass files go through the ADR-045 review step first) with the source
         * indicator and the picked file pending storage.
         */
        fun fromExtraction(
            extraction: BoardingPassExtraction,
            passUri: String,
        ): FlightFormState = fromBoardingPassFlight(extraction.first, extraction.source, passUri)

        /** One reviewed boarding-pass segment → form, file pending storage (ADR-045). */
        fun fromBoardingPassFlight(
            flight: ExtractedFlight,
            source: BoardingPassSource,
            passUri: String,
        ): FlightFormState = fromFlight(flight).copy(prefillSource = source, pendingPassUri = passUri)

        /**
         * Prefill from a booking-confirmation extraction's FIRST segment (the one-way
         * path; multi-flight confirmations go through the ADR-045 review step first).
         * The return-leg hint stays for callers that bypass the review.
         */
        fun fromBookingExtraction(
            extraction: BookingConfirmationExtraction,
            bookingUri: String,
        ): FlightFormState =
            fromBookingFlight(extraction.first, extraction.source, bookingUri)
                .copy(returnLegHint = extraction.additionalFlights > 0)

        /** One reviewed booking-confirmation segment → form, file pending attachment (ADR-045). */
        fun fromBookingFlight(
            flight: ExtractedFlight,
            source: BookingConfirmationSource,
            bookingUri: String,
        ): FlightFormState = fromFlight(flight).copy(bookingSource = source, pendingBookingUri = bookingUri)

        /**
         * Prefill from pasted/shared SMS or email text (ADR-042) — confidence markers per
         * field. Times arrive as `HH:mm` and are re-formatted the way the form shows
         * them; the passenger name is dropped (the form has no such field).
         */
        fun fromTextExtraction(extraction: FlightTextExtraction): FlightFormState {
            fun conf(field: ExtractedField) = field.confidence

            fun timeText(field: ExtractedField): String =
                field.value
                    ?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
                    ?.format(TIME_FORMAT)
                    .orEmpty()

            return FlightFormState(
                airlineIata =
                    extraction.carrier.value
                        .orEmpty()
                        .uppercase(),
                flightNumber = extraction.flightNumber.value.orEmpty(),
                dateText = extraction.flightDate.value.orEmpty(),
                pnr = extraction.pnr.value.orEmpty(),
                seat = extraction.seat.value.orEmpty(),
                cabinClass = extraction.cabinClass.value.orEmpty(),
                depAirport =
                    extraction.fromAirport.value
                        .orEmpty()
                        .uppercase(),
                arrAirport =
                    extraction.toAirport.value
                        .orEmpty()
                        .uppercase(),
                depTimeText = timeText(extraction.depTime),
                arrTimeText = timeText(extraction.arrTime),
                depTerminal = extraction.depTerminal.value.orEmpty(),
                arrTerminal = extraction.arrTerminal.value.orEmpty(),
                confidences =
                    mapOf(
                        FlightField.AIRLINE to conf(extraction.carrier),
                        FlightField.FLIGHT_NUMBER to conf(extraction.flightNumber),
                        FlightField.DATE to conf(extraction.flightDate),
                        FlightField.PNR to conf(extraction.pnr),
                        FlightField.SEAT to conf(extraction.seat),
                        FlightField.CABIN to conf(extraction.cabinClass),
                        FlightField.DEP_AIRPORT to conf(extraction.fromAirport),
                        FlightField.ARR_AIRPORT to conf(extraction.toAirport),
                    ).filterValues { it != ExtractionConfidence.NONE },
                fromText = true,
                returnLegHint = extraction.additionalFlights > 0,
            )
        }

        /** PURE validation. Airline + flight number + date are mandatory; times optional. */
        fun validate(state: FlightFormState): Set<FlightFormError> =
            buildSet {
                if (!AIRLINE_PATTERN.matches(state.airlineIata.trim().uppercase())) {
                    add(FlightFormError.AIRLINE_INVALID)
                }
                if (!FLIGHT_NUMBER_PATTERN.matches(state.flightNumber.trim().uppercase())) {
                    add(FlightFormError.FLIGHT_NUMBER_INVALID)
                }
                if (parseDate(state.dateText) == null) add(FlightFormError.DATE_INVALID)
                if (state.depTimeText.isNotBlank() && parseTime(state.depTimeText) == null) {
                    add(FlightFormError.DEP_TIME_INVALID)
                }
                if (state.arrTimeText.isNotBlank() && parseTime(state.arrTimeText) == null) {
                    add(FlightFormError.ARR_TIME_INVALID)
                }
            }

        fun parseDate(text: String): LocalDate? = runCatching { LocalDate.parse(text.trim(), DATE_FORMAT) }.getOrNull()

        fun parseTime(text: String): LocalTime? = runCatching { LocalTime.parse(text.trim(), TIME_FORMAT) }.getOrNull()

        /** Normalized airport code or empty — invalid entries are simply dropped. */
        private fun airportOrEmpty(text: String): String {
            val trimmed = text.trim().uppercase()
            return if (AIRPORT_PATTERN.matches(trimmed)) trimmed else ""
        }
    }

    /** Converts a VALIDATED state into the domain model (id preserved on edit). */
    fun toJourney(
        checkInUrl: String?,
        zone: ZoneId = ZoneId.systemDefault(),
    ): FlightJourney {
        val date = parseDate(dateText)

        fun instant(timeText: String): Instant? =
            if (date == null) {
                null
            } else {
                parseTime(timeText)?.atDate(date)?.atZone(zone)?.toInstant()
            }

        return FlightJourney(
            id = editingId ?: EntityIds.newId(),
            airlineIata = airlineIata.trim().uppercase(),
            flightNumber = flightNumber.trim().uppercase(),
            date = date,
            pnrBookingRef = pnr.trim(),
            seat = seat.trim(),
            cabinClass = cabinClass.trim(),
            depAirport = airportOrEmpty(depAirport),
            arrAirport = airportOrEmpty(arrAirport),
            schedDep = instant(depTimeText),
            schedArr = instant(arrTimeText),
            depTerminal = depTerminal.trim(),
            arrTerminal = arrTerminal.trim(),
            boardingPassPath = existingPassPath,
            checkInUrl = checkInUrl,
        )
    }
}
