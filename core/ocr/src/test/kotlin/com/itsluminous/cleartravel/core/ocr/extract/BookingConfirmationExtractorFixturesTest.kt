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
 * (both legs on page 1, baggage/fare pages repeating the flights without dates),
 * `blr-lko-confirmation-two-pax` the same OTA itinerary booked for TWO travellers —
 * a second seat-fare / meal row per leg → every segment carries two passengers (ADR-047),
 * `indigo-return-two-pax-pdf` the ML Kit text of a real 9-page IndiGo "Modify Itinerary"
 * print — a page PAIR per passenger (header + `Passenger Information` + DEPARTING leg,
 * then the RETURNED leg), a fare page and four pages of conditions → two segments, both
 * passengers on each, the repeated per-passenger pages folded (ADR-045 amended).
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
                "blr-lko-confirmation-two-pax",
                "indigo-return-two-pax-pdf",
            )
    }
}

/** Non-parameterized companion cases: fallbacks + the ADR-045 segmentation rules. */
class BookingConfirmationExtractorBehaviorTest {
    private val extractor = BookingConfirmationExtractor()

    private fun flights(text: String): List<ExtractedFlight> = extractor.extract(text.trimIndent()).flights

    private fun ExtractedFlight.nameSeat(): List<String> = passengers.map { "${it.name.value} ${it.seat.value}" }

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
    fun `every seat-table row of a route attaches as a passenger (ADR-047)`() {
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
                Bandana Kumari  BLR-LKO  12B  INR 0.00
                Prakash Kumar  LKO-BLR  10A  INR 0.00
                Bandana Kumari  LKO-BLR  10C  INR 0.00
                Bandana Kumari  LKO-BLR  10C  INR 0.00
                """,
            )

        assertThat(result).hasSize(2)
        assertThat(result[0].nameSeat()).containsExactly("PRAKASH KUMAR 12A", "BANDANA KUMARI 12B").inOrder()
        assertThat(result[1].nameSeat()).containsExactly("PRAKASH KUMAR 10A", "BANDANA KUMARI 10C").inOrder()
        // Derived passenger #1 is unchanged from the single-traveller reading.
        assertThat(result.map { it.seat.value }).containsExactly("12A", "10A").inOrder()
    }

    @Test
    fun `a seat row naming the header passenger fills their seat instead of adding a person`() {
        val result =
            flights(
                """
                Traveller: MR PRAKASH KUMAR
                Bengaluru → Lucknow Thu 29 Oct 2026
                BLR 10:30  To  LKO 13:05
                Air India Express 2019  Airline PNR: R87T5G
                Seat Fare
                Bandana Kumari  BLR-LKO  12B  INR 0.00
                Kumar Prakash  BLR-LKO  12A  INR 0.00
                """,
            ).single()

        assertThat(result.nameSeat()).containsExactly("PRAKASH KUMAR 12A", "BANDANA KUMARI 12B").inOrder()
        assertThat(result.passengerName.confidence).isEqualTo(ExtractionConfidence.HIGH)
    }

    @Test
    fun `a nameless seat row fills the first seat-less passenger before counting as a new one`() {
        val result =
            flights(
                """
                Traveller: MR PRAKASH KUMAR
                Bengaluru → Lucknow Thu 29 Oct 2026
                BLR 10:30  To  LKO 13:05
                Air India Express 2019  Airline PNR: R87T5G
                Seat Fare
                BLR-LKO  12A  INR 0.00
                BLR-LKO  12B  INR 0.00
                """,
            ).single()

        assertThat(result.nameSeat()).containsExactly("PRAKASH KUMAR 12A", "null 12B").inOrder()
    }

    @Test
    fun `a passenger table under a NAME header names every row`() {
        val result =
            flights(
                """
                Booking ID: NF712345678901
                6E 6114 BLR - HYD Date: 05-11-2026
                Name  Seat  Meal  Status
                DEV PATEL  --  --  Confirmed
                ANITA PATEL  --  --  Confirmed
                Fare Summary
                """,
            ).single()

        assertThat(result.passengers.map { it.name.value }).containsExactly("DEV PATEL", "ANITA PATEL").inOrder()
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

    // ---- ADR-045 amended (2026-10-29): airline prints that repeat the itinerary per passenger ----

    /** One IndiGo "Modify Itinerary" page pair for [name]: the departing leg, then the return. */
    private fun indigoPages(
        name: String,
        pnr: String = "OC9ISJ",
    ) = """
        24/10/2026, 21:11  Modify ltinerary
        IndiGo  *Date of booking 24th October 2026 21:10
        PNR/Booking Reference $pnr  Confirmed  Payment Status Complete
        Passenger Information
        $name Adult | Male|
        Sector  Seat  6E Add-ons
        PAT-HYD
        HYD-PAT
        DEPARTING  Saver fare
        6E 432 . A320
        Check-in/Bag Drop Closes 11:50
        Travel time 02Hour 10min
        Patna  Hyderabad
        PAT-Jay Prakash Narayan lInternational Airport  HYD-Rajiv Gandhi lInternational Airport
        12:50, 10 Nov 2026  15:00, 10 Nov 2026

        24/10/2026, 21:11  Modify ltinerary
        IndiGo  *Date of booking 24th October 2026 21:10
        PNR/Booking Reference $pnr  Confirmed  Payment Status Complete
        RETURNED Saver fare
        6E 6223. A320
        Check-in/Bag Drop Closes 14:40
        Hyderabad  Travel time 01Hour 55min  Patna
        HYD-Rajiv Gandhi International Airport  PAT-Jay Prakash Narayan International Airport
        15:40, 15 Nov 2026  17:35, 15 Nov 2026
        """.trimIndent()

    @Test
    fun `a page pair per passenger folds into one segment per leg with every passenger`() {
        val result = flights(indigoPages("hari shankar singh") + "\n\n" + indigoPages("ram nath  singh"))

        assertThat(result.map { it.flightKeyForTest() }).containsExactly("6E432", "6E6223").inOrder()
        assertThat(result.map { it.flightDate.value }).containsExactly("2026-11-10", "2026-11-15").inOrder()
        for (segment in result) {
            assertThat(segment.passengers.map { it.name.value })
                .containsExactly("HARI SHANKAR SINGH", "RAM NATH SINGH")
                .inOrder()
            assertThat(segment.pnr.value).isEqualTo("OC9ISJ")
            assertThat(segment.pnr.confidence).isEqualTo(ExtractionConfidence.HIGH)
        }
    }

    @Test
    fun `a passenger information block names the row and drops the type and gender tail`() {
        val result = flights(indigoPages("Mr. hari shankar singh")).first()

        assertThat(result.passengers.map { it.name.value }).containsExactly("HARI SHANKAR SINGH")
        // `Seat  6E Add-ons` is a column header, `6E` the airline: nobody sits in 6E.
        assertThat(
            result.passengers
                .single()
                .seat.isPresent,
        ).isFalse()
    }

    @Test
    fun `departing and returned legs read their dated time row and code-name airports`() {
        val (onward, back) = flights(indigoPages("hari shankar singh"))

        assertThat(onward.fromAirport.value).isEqualTo("PAT")
        assertThat(onward.toAirport.value).isEqualTo("HYD")
        assertThat(onward.depTime.value).isEqualTo("12:50")
        assertThat(onward.arrTime.value).isEqualTo("15:00")
        assertThat(onward.flightDate.value).isEqualTo("2026-11-10")
        assertThat(onward.flightDate.confidence).isEqualTo(ExtractionConfidence.MEDIUM)
        assertThat(back.fromAirport.value).isEqualTo("HYD")
        assertThat(back.toAirport.value).isEqualTo("PAT")
        assertThat(back.depTime.value).isEqualTo("15:40")
        assertThat(back.arrTime.value).isEqualTo("17:35")
        // `6E 432 . A320` / `6E 6223. A320`: the aircraft after the dot is not part of the number.
        assertThat(listOf(onward, back).map { it.flightNumber.value }).containsExactly("432", "6223").inOrder()
    }

    @Test
    fun `a travel-time quantity is never a locator`() {
        val result =
            flights(
                """
                6E 432 PAT-HYD 10 Nov 2026
                Travel time 02Hour 10min
                """,
            ).single()

        assertThat(result.pnr.isPresent).isFalse()
    }

    @Test
    fun `a slash-joined label pair reads the locator and the header PNR beats a bare token`() {
        val labeled = flights("6E 432 PAT-HYD 10 Nov 2026\nPNR/Booking Reference OC9ISJ  Confirmed").single()
        assertThat(labeled.pnr.value).isEqualTo("OC9ISJ")
        assertThat(labeled.pnr.confidence).isEqualTo(ExtractionConfidence.HIGH)

        val two =
            flights(
                """
                PNR/Booking Reference OC9ISJ  Confirmed
                DEPARTING 6E 432 PAT-HYD 10 Nov 2026 ref A1B2C3 printed in the body
                RETURNED 6E 6223 HYD-PAT 15 Nov 2026
                """,
            )
        assertThat(two.map { it.pnr.value }).containsExactly("OC9ISJ", "OC9ISJ")
    }

    @Test
    fun `locator readings that differ by look-alike characters settle on the majority`() {
        val result =
            flights(
                indigoPages("hari shankar singh", pnr = "0C9ISJ") + "\n\n" +
                    indigoPages("ram nath singh", pnr = "OC9ISJ") + "\n\n" +
                    indigoPages("sita devi", pnr = "OC9ISJ"),
            )

        assertThat(result).hasSize(2)
        assertThat(result.map { it.pnr.value }).containsExactly("OC9ISJ", "OC9ISJ")
    }

    @Test
    fun `genuinely different locators are never voted away`() {
        val result =
            flights(
                """
                Bengaluru → Lucknow Thu 29 Oct 2026  Airline PNR: R87T5G
                BLR 10:30  To  LKO 13:05
                Air India Express 2019
                Lucknow → Bengaluru Sat 31 Oct 2026  Airline PNR: D2FE9V
                LKO 20:30  To  BLR 23:00
                Air India Express 1542
                Baggage  Airline PNR: D2FE9V  15 Kgs
                """,
            )

        assertThat(result.map { it.pnr.value }).containsExactly("R87T5G", "D2FE9V").inOrder()
    }

    @Test
    fun `a single OCR reading of a locator is kept as read`() {
        val result = flights("PNR: 0C9ISJ\n6E 432 PAT-HYD 10 Nov 2026").single()

        assertThat(result.pnr.value).isEqualTo("0C9ISJ")
    }

    @Test
    fun `conditions pages quoting example flights add no segments`() {
        val prose =
            FixtureLoader
                .read("indigo-return-two-pax-pdf.txt")
                .split("\n\n")
                .filter { FlightDocumentPages.isProsePage(it) }
        assertThat(prose).hasSize(4) // GST notes + T&C, power banks + fog advisory, terminals, delay/cancellation

        val result = flights(indigoPages("hari shankar singh") + "\n\n" + prose.joinToString("\n\n"))
        assertThat(result.map { it.flightKeyForTest() }).containsExactly("6E432", "6E6223").inOrder()
    }

    @Test
    fun `an OCR'd currency sign before an amount is not a flight`() {
        val result =
            flights(
                """
                6E 432 PAT-HYD 10 Nov 2026
                6E 6223 HYD-PAT 15 Nov 2026
                Flight summary
                Airfare Charge  T29300
                Aviation Security Fee  T944
                Total fare  Z40650
                """,
            )

        assertThat(result.map { it.flightKeyForTest() }).containsExactly("6E432", "6E6223").inOrder()
    }

    private fun ExtractedFlight.flightKeyForTest(): String = carrier.value + flightNumber.value
}
