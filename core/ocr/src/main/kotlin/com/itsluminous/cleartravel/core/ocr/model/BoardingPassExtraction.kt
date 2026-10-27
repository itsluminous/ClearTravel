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
 * heuristics as fallback. ADR-045: a document may hold SEVERAL passes — a multi-leg
 * BCBP (`M2…`), one pass per PDF page, or two passes printed on one page — so the
 * result is a LIST of [ExtractedFlight] segments in document order, deduped by the
 * ADR-025 flight identity (carrier + number + date). A single pass yields exactly one
 * segment. Always succeeds structurally — garbage input yields [EMPTY] (no segments,
 * source NONE), never an exception.
 */
@Serializable
data class BoardingPassExtraction(
    val flights: List<ExtractedFlight> = emptyList(),
    val source: BoardingPassSource = BoardingPassSource.NONE,
) {
    val isEmpty: Boolean get() = this == EMPTY

    /** The first (usually only) segment, or [ExtractedFlight.EMPTY] when nothing was read. */
    val first: ExtractedFlight get() = flights.firstOrNull() ?: ExtractedFlight.EMPTY

    companion object {
        val EMPTY = BoardingPassExtraction()
    }
}
