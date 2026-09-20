package com.itsluminous.cleartravel.core.ocr.bcbp

import com.itsluminous.cleartravel.core.ocr.ExtractionConfidence
import com.itsluminous.cleartravel.core.ocr.model.BoardingPassExtraction
import com.itsluminous.cleartravel.core.ocr.model.BoardingPassSource
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
