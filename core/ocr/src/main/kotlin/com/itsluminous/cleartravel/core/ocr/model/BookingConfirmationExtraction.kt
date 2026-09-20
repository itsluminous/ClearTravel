package com.itsluminous.cleartravel.core.ocr.model

import kotlinx.serialization.Serializable

/** How a booking-confirmation extraction was obtained; drives the form's source hint. */
@Serializable
enum class BookingConfirmationSource {
    /**
     * Decoded from an embedded IATA BCBP barcode — unexpected on e-tickets but
     * authoritative when present; fields are HIGH confidence.
     */
    BARCODE,

    /** Heuristic extraction from OCR text — fields carry per-field confidence. */
    OCR_TEXT,

    /** Nothing recognized — the form opens blank with the file attached. */
    NONE,
}

/**
 * Structured result of a booking-confirmation (e-ticket) import. Unlike boarding
 * passes, confirmations usually carry NO barcode and often describe MULTIPLE flights
 * (return trips): the FIRST flight is extracted fully and [additionalFlights] counts
 * the remaining detected segments so the UI can hint "return leg detected — add it
 * separately". Always succeeds structurally — garbage input yields
 * [BookingConfirmationExtraction.EMPTY], never an exception. Dates are normalized to
 * ISO-8601 `yyyy-MM-dd` when resolvable.
 */
@Serializable
data class BookingConfirmationExtraction(
    val passengerName: ExtractedField = ExtractedField.EMPTY,
    /** Airline PNR (6-char) preferred; falls back to an OTA booking reference. */
    val pnr: ExtractedField = ExtractedField.EMPTY,
    val carrier: ExtractedField = ExtractedField.EMPTY,
    val flightNumber: ExtractedField = ExtractedField.EMPTY,
    /** IATA code when printed; may carry a city name (LOW) when no code is present. */
    val fromAirport: ExtractedField = ExtractedField.EMPTY,
    val toAirport: ExtractedField = ExtractedField.EMPTY,
    val flightDate: ExtractedField = ExtractedField.EMPTY,
    val cabinClass: ExtractedField = ExtractedField.EMPTY,
    /** Often absent on confirmations (seats assigned at check-in) — that's fine. */
    val seat: ExtractedField = ExtractedField.EMPTY,
    /** Detected flight segments BEYOND the first (0 = single one-way flight). */
    val additionalFlights: Int = 0,
    val source: BookingConfirmationSource = BookingConfirmationSource.NONE,
) {
    val isEmpty: Boolean get() = this == EMPTY

    companion object {
        val EMPTY = BookingConfirmationExtraction()
    }
}
