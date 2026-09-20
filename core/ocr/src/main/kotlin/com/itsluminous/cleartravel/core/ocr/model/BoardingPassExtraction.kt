package com.itsluminous.cleartravel.core.ocr.model

import kotlinx.serialization.Serializable

/** How a boarding-pass extraction was obtained; drives the confidence hint in the form. */
@Serializable
enum class BoardingPassSource {
    /** Decoded from the IATA BCBP barcode — authoritative, fields are HIGH confidence. */
    BARCODE,

    /** Heuristic extraction from OCR text — fields carry per-field confidence. */
    OCR_TEXT,

    /** Nothing recognized — the form opens blank with the file attached. */
    NONE,
}

/**
 * Structured result of a boarding-pass import: BCBP barcode decode first, OCR-text
 * heuristics as fallback. Always succeeds structurally — garbage input yields
 * [BoardingPassExtraction.EMPTY], never an exception. Dates are normalized to
 * ISO-8601 `yyyy-MM-dd` when resolvable.
 */
@Serializable
data class BoardingPassExtraction(
    val passengerName: ExtractedField = ExtractedField.EMPTY,
    val pnr: ExtractedField = ExtractedField.EMPTY,
    val carrier: ExtractedField = ExtractedField.EMPTY,
    val flightNumber: ExtractedField = ExtractedField.EMPTY,
    val fromAirport: ExtractedField = ExtractedField.EMPTY,
    val toAirport: ExtractedField = ExtractedField.EMPTY,
    val flightDate: ExtractedField = ExtractedField.EMPTY,
    val seat: ExtractedField = ExtractedField.EMPTY,
    val sequenceNumber: ExtractedField = ExtractedField.EMPTY,
    val source: BoardingPassSource = BoardingPassSource.NONE,
) {
    val isEmpty: Boolean get() = this == EMPTY

    companion object {
        val EMPTY = BoardingPassExtraction()
    }
}
