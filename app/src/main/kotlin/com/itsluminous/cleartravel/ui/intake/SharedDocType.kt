package com.itsluminous.cleartravel.ui.intake

import com.itsluminous.cleartravel.core.ocr.ExtractionConfidence
import com.itsluminous.cleartravel.core.ocr.model.BoardingPassExtraction
import com.itsluminous.cleartravel.core.ocr.model.BoardingPassSource
import com.itsluminous.cleartravel.core.ocr.model.BookingConfirmationExtraction
import com.itsluminous.cleartravel.core.ocr.model.ExtractedField
import com.itsluminous.cleartravel.core.ocr.model.TrainTicketExtraction

/** What a shared PDF/image can be imported as (the intake dialog's options). */
enum class SharedDocType {
    TRAIN_TICKET,
    BOARDING_PASS,
    BOOKING_CONFIRMATION,
}

/**
 * Everything the cheap auto-detect learned about a shared file: the three
 * extractors' results over the same document. Feeds [pickLikelyDocType].
 */
data class SharedDocProbeResult(
    val trainTicket: TrainTicketExtraction = TrainTicketExtraction.EMPTY,
    val boardingPass: BoardingPassExtraction = BoardingPassExtraction.EMPTY,
    val bookingConfirmation: BookingConfirmationExtraction = BookingConfirmationExtraction.EMPTY,
)

/**
 * PRESELECTS the intake option most likely to be right, or null for no preselection:
 * 1. A decoded BCBP barcode is authoritative → boarding pass, no scoring.
 * 2. Otherwise each extractor is scored by its count of non-empty HIGH/MEDIUM
 *    fields (LOW heuristics are too noisy to steer the user); the unique maximum
 *    wins.
 * 3. All-zero scores (garbage) or a tie at the top → null: the user decides.
 */
fun pickLikelyDocType(probe: SharedDocProbeResult): SharedDocType? {
    if (probe.boardingPass.source == BoardingPassSource.BARCODE) return SharedDocType.BOARDING_PASS
    val scores =
        mapOf(
            SharedDocType.TRAIN_TICKET to probe.trainTicket.confidentFieldCount(),
            SharedDocType.BOARDING_PASS to probe.boardingPass.confidentFieldCount(),
            SharedDocType.BOOKING_CONFIRMATION to probe.bookingConfirmation.confidentFieldCount(),
        )
    val best = scores.values.max()
    if (best == 0) return null
    val winners = scores.filterValues { it == best }.keys
    return winners.singleOrNull()
}

private fun ExtractedField.isConfident(): Boolean =
    isPresent && (confidence == ExtractionConfidence.HIGH || confidence == ExtractionConfidence.MEDIUM)

private fun Iterable<ExtractedField>.confidentCount(): Int = count { it.isConfident() }

private fun TrainTicketExtraction.confidentFieldCount(): Int =
    listOf(pnr, trainNumber, trainName, journeyDate, fromStation, toStation, travelClass, quota).confidentCount() +
        passengers.sumOf { listOf(it.name, it.coach, it.berth, it.bookingStatus).confidentCount() }

private fun BoardingPassExtraction.confidentFieldCount(): Int =
    listOf(passengerName, pnr, carrier, flightNumber, fromAirport, toAirport, flightDate, seat, sequenceNumber)
        .confidentCount()

private fun BookingConfirmationExtraction.confidentFieldCount(): Int =
    listOf(passengerName, pnr, carrier, flightNumber, fromAirport, toAirport, flightDate, cabinClass, seat)
        .confidentCount()
