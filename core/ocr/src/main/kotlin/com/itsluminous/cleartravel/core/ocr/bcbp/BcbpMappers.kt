package com.itsluminous.cleartravel.core.ocr.bcbp

import com.itsluminous.cleartravel.core.ocr.ExtractionConfidence
import com.itsluminous.cleartravel.core.ocr.model.BoardingPassExtraction
import com.itsluminous.cleartravel.core.ocr.model.BoardingPassSource
import com.itsluminous.cleartravel.core.ocr.model.BookingConfirmationExtraction
import com.itsluminous.cleartravel.core.ocr.model.BookingConfirmationSource
import com.itsluminous.cleartravel.core.ocr.model.ExtractedField
import com.itsluminous.cleartravel.core.ocr.model.ExtractedFlight

/**
 * Maps ONE decoded BCBP leg to a flight segment. Barcode content is authoritative,
 * so every present field is [ExtractionConfidence.HIGH]; the passenger name is the
 * barcode's shared name block, the compartment code is NOT a cabin name and is left
 * out.
 */
fun BcbpLeg.toExtractedFlight(passengerDisplayName: String): ExtractedFlight =
    ExtractedFlight(
        passengerName = ExtractedField.of(passengerDisplayName, ExtractionConfidence.HIGH),
        pnr = ExtractedField.of(pnr, ExtractionConfidence.HIGH),
        carrier = ExtractedField.of(carrier, ExtractionConfidence.HIGH),
        flightNumber = ExtractedField.of(flightNumber, ExtractionConfidence.HIGH),
        fromAirport = ExtractedField.of(fromAirport, ExtractionConfidence.HIGH),
        toAirport = ExtractedField.of(toAirport, ExtractionConfidence.HIGH),
        flightDate = ExtractedField.of(flightDateIso, ExtractionConfidence.HIGH),
        seat = ExtractedField.of(seat, ExtractionConfidence.HIGH),
        sequenceNumber = ExtractedField.of(sequenceNumber, ExtractionConfidence.HIGH),
    )

/** Every leg of the barcode as a segment (ADR-045: `M2…` = two flights), in barcode order. */
fun BcbpData.toExtractedFlights(): List<ExtractedFlight> = legs.map { it.toExtractedFlight(displayName) }

/** Maps decoded BCBP data (ALL legs) to the boarding-pass prefill result. */
fun BcbpData.toBoardingPassExtraction(): BoardingPassExtraction {
    val flights = toExtractedFlights().filterNot { it.isEmpty }
    if (flights.isEmpty()) return BoardingPassExtraction.EMPTY
    return BoardingPassExtraction(flights = flights, source = BoardingPassSource.BARCODE)
}

/**
 * Maps decoded BCBP data to a booking-confirmation prefill result — used when a
 * confirmation unexpectedly embeds a BCBP barcode (barcode content stays
 * authoritative, so present fields are HIGH). Every leg becomes a segment.
 */
fun BcbpData.toBookingConfirmationExtraction(): BookingConfirmationExtraction {
    val flights = toExtractedFlights().filterNot { it.isEmpty }
    if (flights.isEmpty()) return BookingConfirmationExtraction.EMPTY
    return BookingConfirmationExtraction(flights = flights, source = BookingConfirmationSource.BARCODE)
}
