package com.itsluminous.cleartravel.core.ocr.bcbp

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate

class BcbpParserTest {
    // IATA Resolution 792 canonical single-leg example (60-char mandatory block).
    private val singleLeg = "M1DESMARAIS/LUC       EABC123 YULFRAAC 0834 326J001A0025 100"

    @Test
    fun `parses canonical single leg mandatory block`() {
        val result = BcbpParser.parse(singleLeg, today = LocalDate.of(2025, 11, 1))

        val data = (result as BcbpParseResult.Success).data
        assertThat(data.passengerName).isEqualTo("DESMARAIS/LUC")
        assertThat(data.displayName).isEqualTo("LUC DESMARAIS")
        assertThat(data.legs).hasSize(1)
        with(data.legs.single()) {
            assertThat(pnr).isEqualTo("ABC123")
            assertThat(fromAirport).isEqualTo("YUL")
            assertThat(toAirport).isEqualTo("FRA")
            assertThat(carrier).isEqualTo("AC")
            assertThat(flightNumber).isEqualTo("834")
            assertThat(julianDate).isEqualTo(326)
            assertThat(flightDateIso).isEqualTo("2025-11-22")
            assertThat(compartment).isEqualTo("J")
            assertThat(seat).isEqualTo("1A")
            assertThat(sequenceNumber).isEqualTo("25")
            assertThat(passengerStatus).isEqualTo("1")
        }
    }

    @Test
    fun `parses two legs skipping the conditional field between them`() {
        val name = "SHARMA/RAHUL".padEnd(20)
        val leg1 = "ABC123 " + "DEL" + "BOM" + "6E " + "2345 " + "263" + "Y" + "012A" + "0025 " + "1" + "05"
        val conditional = ">5180" // 5 chars, matching leg1's 0x05 variable-field size
        val leg2 = "ABC123 " + "BOM" + "GOI" + "6E " + "0777 " + "264" + "Y" + "003C" + "0102 " + "1" + "00"
        val raw = "M2$name" + "E" + leg1 + conditional + leg2

        val result = BcbpParser.parse(raw, today = LocalDate.of(2025, 9, 1))

        val data = (result as BcbpParseResult.Success).data
        assertThat(data.legs).hasSize(2)
        with(data.legs[0]) {
            assertThat(fromAirport).isEqualTo("DEL")
            assertThat(toAirport).isEqualTo("BOM")
            assertThat(flightNumber).isEqualTo("2345")
            assertThat(seat).isEqualTo("12A")
            assertThat(flightDateIso).isEqualTo("2025-09-20")
        }
        with(data.legs[1]) {
            assertThat(fromAirport).isEqualTo("BOM")
            assertThat(toAirport).isEqualTo("GOI")
            assertThat(flightNumber).isEqualTo("777")
            assertThat(seat).isEqualTo("3C")
            assertThat(sequenceNumber).isEqualTo("102")
            assertThat(flightDateIso).isEqualTo("2025-09-21")
        }
    }

    @Test
    fun `declared second leg missing is tolerated with one parsed leg`() {
        val truncated = "M2" + singleLeg.substring(2) // says 2 legs, carries only 1
        val result = BcbpParser.parse(truncated, today = LocalDate.of(2025, 11, 1))

        val data = (result as BcbpParseResult.Success).data
        assertThat(data.legs).hasSize(1)
    }

    @Test
    fun `too short input fails`() {
        val result = BcbpParser.parse("M1SHARMA/RAHUL")
        assertThat(result).isInstanceOf(BcbpParseResult.Failure::class.java)
    }

    @Test
    fun `wrong format code fails`() {
        val result = BcbpParser.parse("X" + singleLeg.substring(1))
        assertThat(result).isInstanceOf(BcbpParseResult.Failure::class.java)
    }

    @Test
    fun `zero leg count fails`() {
        val result = BcbpParser.parse("M0" + singleLeg.substring(2))
        assertThat(result).isInstanceOf(BcbpParseResult.Failure::class.java)
    }

    @Test
    fun `blank passenger name fails`() {
        val blankName = "M1" + " ".repeat(20) + singleLeg.substring(22)
        val result = BcbpParser.parse(blankName)
        assertThat(result).isInstanceOf(BcbpParseResult.Failure::class.java)
    }

    @Test
    fun `garbage input fails without throwing`() {
        val result = BcbpParser.parse("lorem ipsum dolor sit amet consectetur adipiscing elit lorem")
        assertThat(result).isInstanceOf(BcbpParseResult.Failure::class.java)
    }

    @Test
    fun `non numeric julian date yields null date but leg still parses`() {
        val badJulian = singleLeg.substring(0, 44) + "XX6" + singleLeg.substring(47)
        val result = BcbpParser.parse(badJulian)

        val leg = (result as BcbpParseResult.Success).data.legs.single()
        assertThat(leg.julianDate).isNull()
        assertThat(leg.flightDateIso).isNull()
    }

    @Test
    fun `julian date resolves across a year boundary`() {
        // Day 2 scanned on Dec 28 is next year's Jan 2, not 360 days ago.
        val resolved = BcbpParser.resolveJulianDate(2, today = LocalDate.of(2025, 12, 28))
        assertThat(resolved).isEqualTo(LocalDate.of(2026, 1, 2))
    }

    @Test
    fun `julian date 366 only lands on a leap year`() {
        val resolved = BcbpParser.resolveJulianDate(366, today = LocalDate.of(2025, 1, 10))
        assertThat(resolved).isEqualTo(LocalDate.of(2024, 12, 31))
    }
}
