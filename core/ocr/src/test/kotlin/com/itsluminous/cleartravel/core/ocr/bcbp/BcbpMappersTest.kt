package com.itsluminous.cleartravel.core.ocr.bcbp

import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.ocr.ExtractionConfidence
import com.itsluminous.cleartravel.core.ocr.model.BoardingPassExtraction
import com.itsluminous.cleartravel.core.ocr.model.BoardingPassSource
import com.itsluminous.cleartravel.core.ocr.model.BookingConfirmationExtraction
import com.itsluminous.cleartravel.core.ocr.model.BookingConfirmationSource
import org.junit.Test
import java.time.LocalDate

class BcbpMappersTest {
    @Test
    fun `maps first leg to a high confidence barcode extraction`() {
        val raw = "M1DESMARAIS/LUC       EABC123 YULFRAAC 0834 326J001A0025 100"
        val data =
            (BcbpParser.parse(raw, today = LocalDate.of(2025, 11, 1)) as BcbpParseResult.Success)
                .data

        val extraction = data.toBoardingPassExtraction()

        assertThat(extraction.source).isEqualTo(BoardingPassSource.BARCODE)
        assertThat(extraction.passengerName.value).isEqualTo("LUC DESMARAIS")
        assertThat(extraction.pnr.value).isEqualTo("ABC123")
        assertThat(extraction.carrier.value).isEqualTo("AC")
        assertThat(extraction.flightNumber.value).isEqualTo("834")
        assertThat(extraction.fromAirport.value).isEqualTo("YUL")
        assertThat(extraction.toAirport.value).isEqualTo("FRA")
        assertThat(extraction.flightDate.value).isEqualTo("2025-11-22")
        assertThat(extraction.seat.value).isEqualTo("1A")
        assertThat(extraction.sequenceNumber.value).isEqualTo("25")
        assertThat(extraction.pnr.confidence).isEqualTo(ExtractionConfidence.HIGH)
        assertThat(extraction.passengerName.confidence).isEqualTo(ExtractionConfidence.HIGH)
    }

    @Test
    fun `no legs maps to the empty extraction`() {
        val extraction = BcbpData(passengerName = "X/Y", legs = emptyList()).toBoardingPassExtraction()
        assertThat(extraction).isEqualTo(BoardingPassExtraction.EMPTY)
    }

    @Test
    fun `maps a barcode to a booking confirmation with additional legs counted`() {
        val leg =
            BcbpLeg(
                pnr = "ABC123",
                fromAirport = "YUL",
                toAirport = "FRA",
                carrier = "AC",
                flightNumber = "834",
                julianDate = 326,
                flightDateIso = "2025-11-22",
                compartment = "J",
                seat = "1A",
                sequenceNumber = "25",
                passengerStatus = "1",
            )
        val returnLeg = leg.copy(fromAirport = "FRA", toAirport = "YUL", flightNumber = "835")

        val extraction =
            BcbpData(passengerName = "DESMARAIS/LUC", legs = listOf(leg, returnLeg))
                .toBookingConfirmationExtraction()

        assertThat(extraction.source).isEqualTo(BookingConfirmationSource.BARCODE)
        assertThat(extraction.passengerName.value).isEqualTo("LUC DESMARAIS")
        assertThat(extraction.pnr.value).isEqualTo("ABC123")
        assertThat(extraction.carrier.value).isEqualTo("AC")
        assertThat(extraction.flightNumber.value).isEqualTo("834")
        assertThat(extraction.fromAirport.value).isEqualTo("YUL")
        assertThat(extraction.toAirport.value).isEqualTo("FRA")
        assertThat(extraction.pnr.confidence).isEqualTo(ExtractionConfidence.HIGH)
        assertThat(extraction.additionalFlights).isEqualTo(1)
    }

    @Test
    fun `no legs maps to the empty booking confirmation`() {
        val extraction = BcbpData(passengerName = "X/Y", legs = emptyList()).toBookingConfirmationExtraction()
        assertThat(extraction).isEqualTo(BookingConfirmationExtraction.EMPTY)
    }
}
