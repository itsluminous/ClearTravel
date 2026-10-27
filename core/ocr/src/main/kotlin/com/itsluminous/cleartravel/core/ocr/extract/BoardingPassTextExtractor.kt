package com.itsluminous.cleartravel.core.ocr.extract

import com.itsluminous.cleartravel.core.ocr.model.BoardingPassExtraction
import com.itsluminous.cleartravel.core.ocr.model.BoardingPassSource
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pure heuristic extractor for boarding-pass OCR text — the fallback when no BCBP
 * barcode is found or it fails to decode. Labeled matches are HIGH confidence,
 * strong unlabeled structural matches MEDIUM, weak heuristics LOW. Always succeeds
 * structurally: garbage input returns [BoardingPassExtraction.EMPTY].
 *
 * ADR-045: one page can carry TWO passes (stacked or side by side, or one per PDF
 * page concatenated by the pipeline), so the text is segmented at a repeated
 * `BOARDING PASS` header / route block / second flight number and each pass is
 * extracted on its own; a single pass yields exactly one segment.
 */
@Singleton
class BoardingPassTextExtractor
    @Inject
    constructor() {
        private val engine = FlightDocumentExtractor(FlightDocumentExtractor.Profile.BOARDING_PASS)

        fun extract(text: String): BoardingPassExtraction =
            runCatching {
                val flights = engine.extract(text)
                if (flights.isEmpty()) {
                    BoardingPassExtraction.EMPTY
                } else {
                    BoardingPassExtraction(flights = flights, source = BoardingPassSource.OCR_TEXT)
                }
            }.getOrDefault(BoardingPassExtraction.EMPTY)
    }
