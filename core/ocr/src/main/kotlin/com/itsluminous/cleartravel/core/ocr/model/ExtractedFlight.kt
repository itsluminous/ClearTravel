package com.itsluminous.cleartravel.core.ocr.model

import kotlinx.serialization.Serializable

/**
 * ONE flight segment read from a travel document (ADR-045). A boarding pass normally
 * yields one, a booking confirmation one per leg, a multi-page "boarding pass" PDF one
 * per page; the document-level results ([BoardingPassExtraction],
 * [BookingConfirmationExtraction]) carry a LIST of these. Every field is an
 * [ExtractedField] with its own confidence (ADR-009 ladder); an unrecognised field is
 * simply [ExtractedField.EMPTY]. Dates are ISO-8601 `yyyy-MM-dd`, times 24-hour
 * `HH:mm`, terminals bare (`2`, not `T2`).
 */
@Serializable
data class ExtractedFlight(
    val passengerName: ExtractedField = ExtractedField.EMPTY,
    /** Airline record locator (6-char) preferred; OTA booking reference as fallback. */
    val pnr: ExtractedField = ExtractedField.EMPTY,
    /** Airline IATA code, e.g. `6E`. */
    val carrier: ExtractedField = ExtractedField.EMPTY,
    /** Flight number without the carrier, leading zeros stripped. */
    val flightNumber: ExtractedField = ExtractedField.EMPTY,
    /** IATA code when printed; may carry a city name (LOW) when no code is present. */
    val fromAirport: ExtractedField = ExtractedField.EMPTY,
    val toAirport: ExtractedField = ExtractedField.EMPTY,
    val flightDate: ExtractedField = ExtractedField.EMPTY,
    val depTime: ExtractedField = ExtractedField.EMPTY,
    val arrTime: ExtractedField = ExtractedField.EMPTY,
    val depTerminal: ExtractedField = ExtractedField.EMPTY,
    val arrTerminal: ExtractedField = ExtractedField.EMPTY,
    val cabinClass: ExtractedField = ExtractedField.EMPTY,
    val seat: ExtractedField = ExtractedField.EMPTY,
    /** Boarding-pass check-in sequence number; absent on confirmations. */
    val sequenceNumber: ExtractedField = ExtractedField.EMPTY,
) {
    val isEmpty: Boolean get() = this == EMPTY

    /**
     * True when the segment carries everything a journey needs to be saved WITHOUT a
     * review form: airline, flight number, date and both airports (ADR-045 direct-save
     * rule; the form's own validation is still applied by the caller).
     */
    val hasMinimumFields: Boolean
        get() =
            carrier.isPresent &&
                flightNumber.isPresent &&
                flightDate.isPresent &&
                fromAirport.isPresent &&
                toAirport.isPresent

    /** Fills every blank field of this segment from [other] (confidence travels with the value). */
    fun fillBlanksFrom(other: ExtractedFlight): ExtractedFlight =
        ExtractedFlight(
            passengerName = passengerName.or(other.passengerName),
            pnr = pnr.or(other.pnr),
            carrier = carrier.or(other.carrier),
            flightNumber = flightNumber.or(other.flightNumber),
            fromAirport = fromAirport.or(other.fromAirport),
            toAirport = toAirport.or(other.toAirport),
            flightDate = flightDate.or(other.flightDate),
            depTime = depTime.or(other.depTime),
            arrTime = arrTime.or(other.arrTime),
            depTerminal = depTerminal.or(other.depTerminal),
            arrTerminal = arrTerminal.or(other.arrTerminal),
            cabinClass = cabinClass.or(other.cabinClass),
            seat = seat.or(other.seat),
            sequenceNumber = sequenceNumber.or(other.sequenceNumber),
        )

    companion object {
        val EMPTY = ExtractedFlight()

        private fun ExtractedField.or(fallback: ExtractedField): ExtractedField = if (isPresent) this else fallback
    }
}
