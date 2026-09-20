package com.itsluminous.cleartravel.core.ocr.model

import kotlinx.serialization.Serializable

/**
 * Structured result of extracting a train ticket from OCR text or a pasted IRCTC
 * SMS/email. Extraction always succeeds structurally: garbage input yields
 * [TrainTicketExtraction.EMPTY] (the add form opens blank), never an exception.
 * Dates are normalized to ISO-8601 `yyyy-MM-dd` when parseable.
 */
@Serializable
data class TrainTicketExtraction(
    val pnr: ExtractedField = ExtractedField.EMPTY,
    val trainNumber: ExtractedField = ExtractedField.EMPTY,
    val trainName: ExtractedField = ExtractedField.EMPTY,
    val journeyDate: ExtractedField = ExtractedField.EMPTY,
    val fromStation: ExtractedField = ExtractedField.EMPTY,
    val toStation: ExtractedField = ExtractedField.EMPTY,
    val travelClass: ExtractedField = ExtractedField.EMPTY,
    val quota: ExtractedField = ExtractedField.EMPTY,
    val passengers: List<PassengerExtraction> = emptyList(),
) {
    val isEmpty: Boolean get() = this == EMPTY

    companion object {
        val EMPTY = TrainTicketExtraction()
    }
}

/** One passenger row from a ticket: name plus coach/berth and booking status when present. */
@Serializable
data class PassengerExtraction(
    val name: ExtractedField = ExtractedField.EMPTY,
    val coach: ExtractedField = ExtractedField.EMPTY,
    val berth: ExtractedField = ExtractedField.EMPTY,
    val bookingStatus: ExtractedField = ExtractedField.EMPTY,
)
