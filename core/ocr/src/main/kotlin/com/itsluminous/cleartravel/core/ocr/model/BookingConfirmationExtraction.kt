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
 * Structured result of a booking-confirmation (e-ticket) import. Confirmations often
 * describe SEVERAL flights (return trips, multi-city itineraries): ADR-045 makes the
 * result a LIST of [ExtractedFlight] segments in document order — one per distinct
 * carrier + flight number + date — each with its own PNR, route, times, terminals,
 * cabin and seat; hotel / fare / meal sections never contribute a segment. A one-way
 * confirmation yields exactly one segment. Always succeeds structurally — garbage
 * input yields [EMPTY], never an exception. Dates are ISO-8601 `yyyy-MM-dd`.
 */
@Serializable
data class BookingConfirmationExtraction(
    val flights: List<ExtractedFlight> = emptyList(),
    val source: BookingConfirmationSource = BookingConfirmationSource.NONE,
) {
    val isEmpty: Boolean get() = this == EMPTY

    /** The first (usually only) segment, or [ExtractedFlight.EMPTY] when nothing was read. */
    val first: ExtractedFlight get() = flights.firstOrNull() ?: ExtractedFlight.EMPTY

    /** Detected flight segments BEYOND the first (0 = single one-way flight). */
    val additionalFlights: Int get() = (flights.size - 1).coerceAtLeast(0)

    companion object {
        val EMPTY = BookingConfirmationExtraction()
    }
}
