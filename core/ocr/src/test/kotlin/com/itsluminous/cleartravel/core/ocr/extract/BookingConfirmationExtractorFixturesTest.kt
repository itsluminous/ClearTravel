package com.itsluminous.cleartravel.core.ocr.extract

import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.ocr.ExtractionConfidence
import com.itsluminous.cleartravel.core.ocr.FixtureLoader
import com.itsluminous.cleartravel.core.ocr.model.BookingConfirmationExtraction
import com.itsluminous.cleartravel.core.ocr.model.ExtractedFlight
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Fixture harness for the booking-confirmation OCR-text extractor: every recorded
 * fixture is extracted and compared against its expected-output JSON — a field table
 * PER SEGMENT (ADR-003 discipline; ADR-017, ADR-045). `blr-lko-confirmation` is the
 * ML Kit text of the real OTA itinerary image (two Air India Express legs + a hotel),
 * `blr-lko-confirmation-pdf` the ML Kit text of a real 3-page MakeMyTrip e-ticket
 * (both legs on page 1, baggage/fare pages repeating the flights without dates).
 */
@RunWith(Parameterized::class)
class BookingConfirmationExtractorFixturesTest(
    private val fixture: String,
) {
    private val extractor = BookingConfirmationExtractor()

    @Test
    fun extractsExpectedSegments() {
        val text = FixtureLoader.read("$fixture.txt")
        val expected =
            Json.decodeFromString<BookingConfirmationExtraction>(
                FixtureLoader.read("$fixture.expected.json"),
            )

        assertThat(extractor.extract(text)).isEqualTo(expected)
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun fixtures() =
            listOf(
                "booking-airline-1",
                "booking-ota-1",
                "booking-return-1",
                "booking-oneway-hotel-1",
                "blr-lko-confirmation",
                "blr-lko-confirmation-pdf",
            )
    }
}

/** Non-parameterized companion cases: fallbacks + the ADR-045 segmentation rules. */
class BookingConfirmationExtractorBehaviorTest {
    private val extractor = BookingConfirmationExtractor()

    private fun flights(text: String): List<ExtractedFlight> = extractor.extract(text.trimIndent()).flights

    @Test
    fun `garbage fixture falls back to the empty result`() {
        val result = extractor.extract(FixtureLoader.read("garbage.txt"))
        assertThat(result).isEqualTo(BookingConfirmationExtraction.EMPTY)
        assertThat(result.isEmpty).isTrue()
        assertThat(result.flights).isEmpty()
        assertThat(result.additionalFlights).isEqualTo(0)
    }

    @Test
    fun `empty and hostile input never throw`() {
        assertThat(extractor.extract("")).isEqualTo(BookingConfirmationExtraction.EMPTY)
        assertThat(extractor.extract("\u0000\uFFFD\n\t ~~~ ((( "))
            .isEqualTo(BookingConfirmationExtraction.EMPTY)
    }

    @Test
    fun `a single-flight document yields exactly one segment`() {
        val result = extractor.extract(FixtureLoader.read("booking-airline-1.txt"))
        assertThat(result.flights).hasSize(1)
        assertThat(result.additionalFlights).isEqualTo(0)
        assertThat(result.first.flightNumber.value).isEqualTo("865")
    }

    @Test
    fun `return trip yields two segments each with its own route and date`() {
        val result =
            flights(
                """
                PNR: A1B2C3
                Onward 6E 2001 DEL - GOI Date: 10-01-2027
                Return 6E 2002 GOI - DEL Date: 17-01-2027
                """,
            )

        assertThat(result).hasSize(2)
        with(result[0]) {
            assertThat(carrier.value).isEqualTo("6E")
            assertThat(flightNumber.value).isEqualTo("2001")
            assertThat(fromAirport.value).isEqualTo("DEL")
            assertThat(toAirport.value).isEqualTo("GOI")
            assertThat(flightDate.value).isEqualTo("2027-01-10")
            assertThat(pnr.value).isEqualTo("A1B2C3") // shared from the preamble
        }
        with(result[1]) {
            assertThat(flightNumber.value).isEqualTo("2002")
            assertThat(fromAirport.value).isEqualTo("GOI")
            assertThat(toAirport.value).isEqualTo("DEL")
            assertThat(flightDate.value).isEqualTo("2027-01-17")
            assertThat(pnr.value).isEqualTo("A1B2C3")
        }
    }

    @Test
    fun `repeated mentions of the same flight and date collapse into one segment`() {
        val result =
            flights(
                """
                Flight AI 0865 New Delhi (DEL) - Mumbai (BOM) Date: 12 Oct 2026
                Flight AI 0865 New Delhi (DEL) - Mumbai (BOM) Date: 12 Oct 2026
                Fare summary for AI 0865: INR 7,845
                """,
            )

        assertThat(result).hasSize(1)
        assertThat(result.single().flightNumber.value).isEqualTo("865")
    }

    @Test
    fun `the same flight number on two different dates is two segments`() {
        val result =
            flights(
                """
                PNR: A1B2C3
                Onward 6E 2001 DEL - GOI Date: 10-01-2027
                Return 6E 2001 GOI - DEL Date: 17-01-2027
                """,
            )

        assertThat(result.map { it.flightDate.value }).containsExactly("2027-01-10", "2027-01-17").inOrder()
        assertThat(result.map { it.fromAirport.value }).containsExactly("DEL", "GOI").inOrder()
    }

    @Test
    fun `an undated later mention of a flight folds into its dated segment`() {
        val result =
            flights(
                """
                Air India Express IX-2019 PNR: X3C9GI
                BLR 10:30  2h 35m  13:05 LKO
                Thu, 29 Oct 2026
                Baggage Information
                BLR - LKO, Air India Express IX-2019
                Adult  7 Kgs  15 Kgs
                """,
            )

        assertThat(result).hasSize(1)
        assertThat(result.single().flightDate.value).isEqualTo("2026-10-29")
    }

    @Test
    fun `a hotel block between two flights pollutes neither segment`() {
        val result =
            flights(
                """
                Booking ID: CD-874068
                Bengaluru → Lucknow Thu 29 Oct 2026
                BLR 10:30  To  LKO 13:05
                Air India Express 2019  Airline PNR: R87T5G
                Hotel
                La Place Sarovar Portico
                Check-In: Thu 29 Oct 2026  Check-Out: Sat 31 Oct 2026
                Stay Period  2 Nights
                Lucknow → Bengaluru Sat 31 Oct 2026
                LKO 20:30  To  BLR 23:00
                Air India Express 1542  Airline PNR: D2FE9V
                """,
            )

        assertThat(result).hasSize(2)
        assertThat(result[0].flightDate.value).isEqualTo("2026-10-29")
        assertThat(result[0].pnr.value).isEqualTo("R87T5G")
        assertThat(result[1].flightDate.value).isEqualTo("2026-10-31")
        assertThat(result[1].pnr.value).isEqualTo("D2FE9V")
        assertThat(result[1].fromAirport.value).isEqualTo("LKO")
        assertThat(result[1].depTime.value).isEqualTo("20:30")
    }

    @Test
    fun `seat fare rows attach to their segment by route`() {
        val result =
            flights(
                """
                Bengaluru → Lucknow Thu 29 Oct 2026
                BLR 10:30  To  LKO 13:05
                Air India Express 2019  Airline PNR: R87T5G
                Lucknow → Bengaluru Sat 31 Oct 2026
                LKO 20:30  To  BLR 23:00
                Air India Express 1542  Airline PNR: D2FE9V
                Seat Fare
                Prakash Kumar  BLR-LKO  12A  INR 0.00
                Prakash Kumar  LKO-BLR  10A  INR 0.00
                """,
            )

        assertThat(result.map { it.seat.value }).containsExactly("12A", "10A").inOrder()
        assertThat(result.map { it.seat.confidence }).containsExactly(ExtractionConfidence.MEDIUM, ExtractionConfidence.MEDIUM)
        assertThat(result.map { it.passengerName.value }).containsExactly("PRAKASH KUMAR", "PRAKASH KUMAR")
    }

    @Test
    fun `an airline PNR near the segment beats the booking id and a second PNR opens a segment`() {
        val result =
            flights(
                """
                Booking ID: CD-874068
                Bengaluru → Lucknow Thu 29 Oct 2026  Confirmed PNR R87T5G
                BLR 10:30  To  LKO 13:05
                Air India Express 2019
                Lucknow Bengaluru Sat 31 Oct 2026  Confirmed PNR D2FE9V
                LKO 20:30  To  BLR 23:00
                Air India Express 1542
                """,
            )

        assertThat(result).hasSize(2)
        assertThat(result[0].pnr.value).isEqualTo("R87T5G")
        assertThat(result[0].pnr.confidence).isEqualTo(ExtractionConfidence.HIGH)
        assertThat(result[1].pnr.value).isEqualTo("D2FE9V")
    }

    @Test
    fun `ota booking reference is used at medium confidence when no airline pnr is printed`() {
        val result =
            flights(
                """
                Booking ID: NF712345678901
                Traveller: MR DEV PATEL
                6E 6114 BLR - HYD Date: 05-11-2026
                """,
            ).single()

        assertThat(result.pnr.value).isEqualTo("NF712345678901")
        assertThat(result.pnr.confidence).isEqualTo(ExtractionConfidence.MEDIUM)
    }

    @Test
    fun `airline name without a code resolves the carrier through the alias table`() {
        val result = flights("IndiGo 6114 Travel Date: 05-11-2026 BLR - HYD").single()

        assertThat(result.carrier.value).isEqualTo("6E")
        assertThat(result.carrier.confidence).isEqualTo(ExtractionConfidence.MEDIUM)
        assertThat(result.flightNumber.value).isEqualTo("6114")
    }

    @Test
    fun `durations weights and aircraft types are never flight numbers`() {
        val result =
            flights(
                """
                Air India Express IX-2019 Economy
                Boeing 737 Max 8   Airbus A320
                7 Kgs 15 Kgs   2h 35m duration
                BLR 10:30  13:05 LKO  Thu, 29 Oct 2026
                """,
            )

        assertThat(result).hasSize(1)
        assertThat(result.single().flightNumber.value).isEqualTo("2019")
    }

    @Test
    fun `terminals follow the departure and arrival columns`() {
        // One row: both terminals, departure cell first.
        val sameRow = flights("IX-2019 BLR 10:30  13:05 LKO  12 Oct 2026\nTerminal - T2, Kempegowda  Terminal - T3, Amausi").single()
        assertThat(sameRow.depTerminal.value).isEqualTo("2")
        assertThat(sameRow.arrTerminal.value).isEqualTo("3")

        // Stacked rows: the one in a later cell is under the arrival column.
        val stacked = flights("IX-2019 BLR 10:30  13:05 LKO  12 Oct 2026\nEconomy (L)  Terminal:3\nTerminal: 2").single()
        assertThat(stacked.depTerminal.value).isEqualTo("2")
        assertThat(stacked.arrTerminal.value).isEqualTo("3")

        // A labeled departure keeps its terminal even when printed in a later cell.
        val labeled = flights("Flight AI 0865 DEL - BOM Date: 12 Oct 2026\nDeparture 06:15   Terminal 3").single()
        assertThat(labeled.depTerminal.value).isEqualTo("3")
        assertThat(labeled.arrTerminal.isPresent).isFalse()
    }

    @Test
    fun `times come from the itinerary block and am-pm labels are normalised`() {
        val block = flights("IX-2019 Thu 29 Oct 2026\nBLR 10:30  2h 35m  13:05 LKO").single()
        assertThat(block.depTime.value).isEqualTo("10:30")
        assertThat(block.arrTime.value).isEqualTo("13:05")

        val labeled = flights("6E 6114 BLR - HYD Travel Date: 05-11-2026\nDeparture 08:30 PM  Arrival 10:05 PM").single()
        assertThat(labeled.depTime.value).isEqualTo("20:30")
        assertThat(labeled.arrTime.value).isEqualTo("22:05")
        assertThat(labeled.depTime.confidence).isEqualTo(ExtractionConfidence.HIGH)
    }

    @Test
    fun `minimum-field check needs airline number date and both airports`() {
        val complete = flights("6E 6114 BLR - HYD Travel Date: 05-11-2026").single()
        assertThat(complete.hasMinimumFields).isTrue()

        val noRoute = flights("6E 6114 Travel Date: 05-11-2026").single()
        assertThat(noRoute.hasMinimumFields).isFalse()
    }
}
