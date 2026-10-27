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
    private val leg =
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

    @Test
    fun `maps a single leg to a high confidence barcode extraction`() {
        val raw = "M1DESMARAIS/LUC       EABC123 YULFRAAC 0834 326J001A0025 100"
        val data =
            (BcbpParser.parse(raw, today = LocalDate.of(2025, 11, 1)) as BcbpParseResult.Success)
                .data

        val extraction = data.toBoardingPassExtraction()

        assertThat(extraction.source).isEqualTo(BoardingPassSource.BARCODE)
        assertThat(extraction.flights).hasSize(1)
        with(extraction.first) {
            assertThat(passengerName.value).isEqualTo("LUC DESMARAIS")
            assertThat(pnr.value).isEqualTo("ABC123")
            assertThat(carrier.value).isEqualTo("AC")
            assertThat(flightNumber.value).isEqualTo("834")
            assertThat(fromAirport.value).isEqualTo("YUL")
            assertThat(toAirport.value).isEqualTo("FRA")
            assertThat(flightDate.value).isEqualTo("2025-11-22")
            assertThat(seat.value).isEqualTo("1A")
            assertThat(sequenceNumber.value).isEqualTo("25")
            assertThat(pnr.confidence).isEqualTo(ExtractionConfidence.HIGH)
            assertThat(passengerName.confidence).isEqualTo(ExtractionConfidence.HIGH)
            assertThat(cabinClass.isPresent).isFalse() // compartment code is not a cabin name
        }
    }

    /** ADR-045: an `M2` barcode (two legs) is two segments sharing the passenger. */
    @Test
    fun `a two-leg barcode maps to two segments`() {
        val name = "SHARMA/RAHUL".padEnd(20)
        val leg1 = "ABC123 " + "DEL" + "BOM" + "6E " + "2345 " + "263" + "Y" + "012A" + "0025 " + "1" + "05"
        val conditional = ">5180"
        val leg2 = "ABC123 " + "BOM" + "GOI" + "6E " + "0777 " + "264" + "Y" + "003C" + "0102 " + "1" + "00"
        val raw = "M2$name" + "E" + leg1 + conditional + leg2
        val data = (BcbpParser.parse(raw, today = LocalDate.of(2025, 9, 1)) as BcbpParseResult.Success).data

        val extraction = data.toBoardingPassExtraction()

        assertThat(extraction.source).isEqualTo(BoardingPassSource.BARCODE)
        assertThat(extraction.flights).hasSize(2)
        assertThat(extraction.flights.map { it.passengerName.value }).containsExactly("RAHUL SHARMA", "RAHUL SHARMA")
        assertThat(extraction.flights.map { it.flightNumber.value }).containsExactly("2345", "777").inOrder()
        assertThat(extraction.flights.map { it.fromAirport.value }).containsExactly("DEL", "BOM").inOrder()
        assertThat(extraction.flights.map { it.toAirport.value }).containsExactly("BOM", "GOI").inOrder()
        assertThat(extraction.flights.map { it.seat.value }).containsExactly("12A", "3C").inOrder()
        assertThat(extraction.flights.map { it.flightDate.value }).containsExactly("2025-09-20", "2025-09-21").inOrder()
        assertThat(extraction.flights.all { it.hasMinimumFields }).isTrue()
    }

    @Test
    fun `no legs maps to the empty extraction`() {
        val extraction = BcbpData(passengerName = "X/Y", legs = emptyList()).toBoardingPassExtraction()
        assertThat(extraction).isEqualTo(BoardingPassExtraction.EMPTY)
    }

    @Test
    fun `maps a barcode to a booking confirmation with every leg as a segment`() {
        val returnLeg = leg.copy(fromAirport = "FRA", toAirport = "YUL", flightNumber = "835")

        val extraction =
            BcbpData(passengerName = "DESMARAIS/LUC", legs = listOf(leg, returnLeg))
                .toBookingConfirmationExtraction()

        assertThat(extraction.source).isEqualTo(BookingConfirmationSource.BARCODE)
        assertThat(extraction.flights).hasSize(2)
        assertThat(extraction.additionalFlights).isEqualTo(1)
        with(extraction.first) {
            assertThat(passengerName.value).isEqualTo("LUC DESMARAIS")
            assertThat(pnr.value).isEqualTo("ABC123")
            assertThat(carrier.value).isEqualTo("AC")
            assertThat(flightNumber.value).isEqualTo("834")
            assertThat(fromAirport.value).isEqualTo("YUL")
            assertThat(toAirport.value).isEqualTo("FRA")
            assertThat(pnr.confidence).isEqualTo(ExtractionConfidence.HIGH)
        }
        assertThat(extraction.flights[1].flightNumber.value).isEqualTo("835")
    }

    @Test
    fun `no legs maps to the empty booking confirmation`() {
        val extraction = BcbpData(passengerName = "X/Y", legs = emptyList()).toBookingConfirmationExtraction()
        assertThat(extraction).isEqualTo(BookingConfirmationExtraction.EMPTY)
    }
}
