package com.itsluminous.cleartravel.core.ocr.bcbp

import com.itsluminous.cleartravel.core.ocr.ExtractionConfidence
import com.itsluminous.cleartravel.core.ocr.model.BoardingPassExtraction
import com.itsluminous.cleartravel.core.ocr.model.BoardingPassSource
import com.itsluminous.cleartravel.core.ocr.model.BookingConfirmationExtraction
import com.itsluminous.cleartravel.core.ocr.model.BookingConfirmationSource
import com.itsluminous.cleartravel.core.ocr.model.ExtractedField

/**
 * Maps decoded BCBP data (first leg) to the prefill result. Barcode content is
 * authoritative, so every present field is [ExtractionConfidence.HIGH].
 */
fun BcbpData.toBoardingPassExtraction(): BoardingPassExtraction {
    val leg = legs.firstOrNull() ?: return BoardingPassExtraction.EMPTY
    return BoardingPassExtraction(
        passengerName = ExtractedField.of(displayName, ExtractionConfidence.HIGH),
        pnr = ExtractedField.of(leg.pnr, ExtractionConfidence.HIGH),
        carrier = ExtractedField.of(leg.carrier, ExtractionConfidence.HIGH),
        flightNumber = ExtractedField.of(leg.flightNumber, ExtractionConfidence.HIGH),
        fromAirport = ExtractedField.of(leg.fromAirport, ExtractionConfidence.HIGH),
        toAirport = ExtractedField.of(leg.toAirport, ExtractionConfidence.HIGH),
        flightDate = ExtractedField.of(leg.flightDateIso, ExtractionConfidence.HIGH),
        seat = ExtractedField.of(leg.seat, ExtractionConfidence.HIGH),
        sequenceNumber = ExtractedField.of(leg.sequenceNumber, ExtractionConfidence.HIGH),
        source = BoardingPassSource.BARCODE,
    )
}

/**
 * Maps decoded BCBP data to a booking-confirmation prefill result — used when a
 * confirmation unexpectedly embeds a BCBP barcode (barcode content stays
 * authoritative, so present fields are HIGH). The first leg is extracted fully;
 * further legs surface as `additionalFlights` (return-leg hint).
 */
fun BcbpData.toBookingConfirmationExtraction(): BookingConfirmationExtraction {
    val pass = toBoardingPassExtraction()
    if (pass.isEmpty) return BookingConfirmationExtraction.EMPTY
    return BookingConfirmationExtraction(
        passengerName = pass.passengerName,
        pnr = pass.pnr,
        carrier = pass.carrier,
        flightNumber = pass.flightNumber,
        fromAirport = pass.fromAirport,
        toAirport = pass.toAirport,
        flightDate = pass.flightDate,
        seat = pass.seat,
        additionalFlights = (legs.size - 1).coerceAtLeast(0),
        source = BookingConfirmationSource.BARCODE,
    )
}
