package com.itsluminous.cleartravel.core.ocr.extract

import com.itsluminous.cleartravel.core.ocr.model.BookingConfirmationExtraction
import com.itsluminous.cleartravel.core.ocr.model.BookingConfirmationSource
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pure heuristic extractor for booking-confirmation / e-ticket OCR text (airline
 * direct AND OTA layouts). Same discipline as [BoardingPassTextExtractor]: labeled
 * matches are HIGH confidence, strong unlabeled structural matches MEDIUM, weak
 * heuristics LOW; always succeeds structurally — garbage input returns
 * [BookingConfirmationExtraction.EMPTY].
 *
 * ADR-045: confirmations often describe MULTIPLE flights (return trips, multi-city).
 * The text is segmented per flight ([FlightSegmenter]) and every segment is extracted
 * on its own — PNR, route, date, times, terminals, cabin, seat — so the result is a
 * LIST of segments; hotel, fare and meal sections never contribute one, and the
 * `Seat Fare` table's rows are attached back to their segment by route. A one-way
 * confirmation yields exactly one segment with the pre-ADR-045 values.
 */
@Singleton
class BookingConfirmationExtractor
    @Inject
    constructor() {
        private val engine = FlightDocumentExtractor(FlightDocumentExtractor.Profile.CONFIRMATION)

        fun extract(text: String): BookingConfirmationExtraction =
            runCatching {
                val flights = engine.extract(text)
                if (flights.isEmpty()) {
                    BookingConfirmationExtraction.EMPTY
                } else {
                    BookingConfirmationExtraction(flights = flights, source = BookingConfirmationSource.OCR_TEXT)
                }
            }.getOrDefault(BookingConfirmationExtraction.EMPTY)
    }
