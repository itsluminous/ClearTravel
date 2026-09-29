package com.itsluminous.cleartravel.feature.flights.text

import com.itsluminous.cleartravel.core.ocr.model.ExtractedField
import kotlinx.serialization.Serializable

/**
 * Structured result of reading a flight from pasted/shared SMS or email text
 * (ADR-042). Same [ExtractedField] discipline as the OCR extractions in `core:ocr`:
 * per-field confidence, the user reviews the prefilled form, nothing is saved blind.
 * Always succeeds structurally — text without a flight yields [EMPTY], never an
 * exception. Dates are ISO-8601 `yyyy-MM-dd`, times 24-hour `HH:mm`.
 */
@Serializable
data class FlightTextExtraction(
    /** Greeting/label name — the flight form has no passenger field, kept for review UIs. */
    val passengerName: ExtractedField = ExtractedField.EMPTY,
    /** 6-character airline record locator. */
    val pnr: ExtractedField = ExtractedField.EMPTY,
    /** Airline IATA code, e.g. "QP". */
    val carrier: ExtractedField = ExtractedField.EMPTY,
    /** Airline display name from the rules table, e.g. "Akasa Air". */
    val airlineName: ExtractedField = ExtractedField.EMPTY,
    /** Numeric flight number without the carrier, e.g. "1421". */
    val flightNumber: ExtractedField = ExtractedField.EMPTY,
    val fromAirport: ExtractedField = ExtractedField.EMPTY,
    val toAirport: ExtractedField = ExtractedField.EMPTY,
    val flightDate: ExtractedField = ExtractedField.EMPTY,
    val depTime: ExtractedField = ExtractedField.EMPTY,
    val arrTime: ExtractedField = ExtractedField.EMPTY,
    val depTerminal: ExtractedField = ExtractedField.EMPTY,
    val arrTerminal: ExtractedField = ExtractedField.EMPTY,
    val seat: ExtractedField = ExtractedField.EMPTY,
    val cabinClass: ExtractedField = ExtractedField.EMPTY,
    /** Detected flight segments BEYOND the first (return legs) — 0 for a single flight. */
    val additionalFlights: Int = 0,
) {
    val isEmpty: Boolean get() = this == EMPTY

    companion object {
        val EMPTY = FlightTextExtraction()
    }
}
