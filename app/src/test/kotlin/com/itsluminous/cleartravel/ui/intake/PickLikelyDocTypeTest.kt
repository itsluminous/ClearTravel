package com.itsluminous.cleartravel.ui.intake

import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.ocr.ExtractionConfidence
import com.itsluminous.cleartravel.core.ocr.model.BoardingPassExtraction
import com.itsluminous.cleartravel.core.ocr.model.BoardingPassSource
import com.itsluminous.cleartravel.core.ocr.model.BookingConfirmationExtraction
import com.itsluminous.cleartravel.core.ocr.model.BookingConfirmationSource
import com.itsluminous.cleartravel.core.ocr.model.ExtractedField
import com.itsluminous.cleartravel.core.ocr.model.TrainTicketExtraction
import org.junit.Test

class PickLikelyDocTypeTest {
    private fun high(value: String) = ExtractedField.of(value, ExtractionConfidence.HIGH)

    private fun medium(value: String) = ExtractedField.of(value, ExtractionConfidence.MEDIUM)

    private fun low(value: String) = ExtractedField.of(value, ExtractionConfidence.LOW)

    private val richTrain =
        TrainTicketExtraction(
            pnr = high("8524317690"),
            trainNumber = high("12951"),
            fromStation = medium("MMCT"),
            toStation = medium("NDLS"),
        )

    @Test
    fun `decoded barcode wins even when other extractors score higher`() {
        val probe =
            SharedDocProbeResult(
                trainTicket = richTrain,
                boardingPass = BoardingPassExtraction(pnr = high("ABC123"), source = BoardingPassSource.BARCODE),
            )

        assertThat(pickLikelyDocType(probe)).isEqualTo(SharedDocType.BOARDING_PASS)
    }

    @Test
    fun `train ticket wins on confident field count`() {
        val probe =
            SharedDocProbeResult(
                trainTicket = richTrain,
                boardingPass = BoardingPassExtraction(pnr = low("8524317690"), source = BoardingPassSource.OCR_TEXT),
                bookingConfirmation = BookingConfirmationExtraction(pnr = medium("MMT123456"), source = BookingConfirmationSource.OCR_TEXT),
            )

        assertThat(pickLikelyDocType(probe)).isEqualTo(SharedDocType.TRAIN_TICKET)
    }

    @Test
    fun `booking confirmation wins when it has the most confident fields`() {
        val probe =
            SharedDocProbeResult(
                bookingConfirmation =
                    BookingConfirmationExtraction(
                        pnr = high("X7Y8Z9"),
                        carrier = high("AI"),
                        flightNumber = high("0865"),
                        flightDate = medium("2026-10-01"),
                        source = BookingConfirmationSource.OCR_TEXT,
                    ),
                boardingPass = BoardingPassExtraction(carrier = medium("AI"), source = BoardingPassSource.OCR_TEXT),
            )

        assertThat(pickLikelyDocType(probe)).isEqualTo(SharedDocType.BOOKING_CONFIRMATION)
    }

    @Test
    fun `low confidence fields do not count`() {
        val probe =
            SharedDocProbeResult(
                trainTicket = TrainTicketExtraction(pnr = low("1234567890"), trainNumber = low("12345")),
                boardingPass = BoardingPassExtraction(pnr = medium("ABC123"), source = BoardingPassSource.OCR_TEXT),
            )

        assertThat(pickLikelyDocType(probe)).isEqualTo(SharedDocType.BOARDING_PASS)
    }

    @Test
    fun `garbage yields no preselection`() {
        assertThat(pickLikelyDocType(SharedDocProbeResult())).isNull()
    }

    @Test
    fun `a tie at the top yields no preselection`() {
        val probe =
            SharedDocProbeResult(
                trainTicket = TrainTicketExtraction(pnr = high("1234567890")),
                bookingConfirmation = BookingConfirmationExtraction(pnr = high("ABC123"), source = BookingConfirmationSource.OCR_TEXT),
            )

        assertThat(pickLikelyDocType(probe)).isNull()
    }
}
