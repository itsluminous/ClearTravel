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
 * `agency-trip-connecting-3-legs` / `-2-legs` are the ML Kit text of two agency
 * trip-view screenshots (ADR-050): connecting one-way itineraries printed as vertical
 * leg blocks — `11:05 PM • 21 Nov 2026` over `(SEA) Seattle-Tacoma …`, an
 * `Airline/Flight #  Alaska Airlines / AS22` cell per leg, layover rows between legs, an
 * agency AND an airline confirmation in the header, no passengers or terminals →
 * three (SEA→JFK→BOM→BLR, overnight arrivals dated) and two (BLR→AMS→SEA) segments.
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
                "agency-trip-connecting-3-legs",
                "agency-trip-connecting-2-legs",
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

    // ---- ADR-050: connecting legs in an agency / corporate trip view ----------------

    /** One vertical leg block as the trip view prints it; [arrivalStamp] defaults to a same-day arrival. */
    private fun legBlock(
        depTime: String,
        depDate: String,
        depAirport: String,
        arrTime: String,
        arrAirport: String,
        airlineCell: String,
        arrivalStamp: String = "$arrTime • $depDate",
    ) = """
        $depTime • $depDate
        ($depAirport) Some International Airport
        Travel time: 5h 19m
        $arrivalStamp
        ($arrAirport) Another International Airport
        Seat
        Airline/Flight #  $airlineCell Booked
        """.trimIndent()

    private val header =
        """
        Bengaluru, IN
        Sat, 21 Nov 2026 - Mon, 23 Nov 2026
        Agency confirmation number:  8IUT7P
        Alaska Airlines  Air India
        AS22
        One-way trip
        Airline confirmation  HVLYKH
        CO, emissions  1885.08 kg
        """.trimIndent()

    @Test
    fun `stacked endpoint rows read route times and per-leg dates, each leg a segment`() {
        val result =
            flights(
                header + "\n" +
                    legBlock("11:05 PM", "21 Nov 2026", "SEA", "7:24 AM", "JFK", "Alaska Airlines / AS22", "7:24 AM • 22 Nov 2026 +1d") +
                    "\nLayover 2h 41m • New York (JFK)\n" +
                    legBlock("10:05 AM", "22 Nov 2026", "JFK", "11:10 AM", "BOM", "Air India / AI116", "11:10 AM 23 Nov 2026*1d") +
                    "\nLayover 4h 25m • Mumbai (BOM)\n" +
                    legBlock("3:35 PM", "23 Nov 2026", "BOM", "5:25 PM", "BLR", "Air India / AI2853"),
            )

        assertThat(result.map { it.flightKeyForTest() }).containsExactly("AS22", "AI116", "AI2853").inOrder()
        assertThat(result.map { "${it.fromAirport.value}-${it.toAirport.value}" }).containsExactly("SEA-JFK", "JFK-BOM", "BOM-BLR").inOrder()
        assertThat(result.map { it.depTime.value }).containsExactly("23:05", "10:05", "15:35").inOrder()
        assertThat(result.map { it.arrTime.value }).containsExactly("07:24", "11:10", "17:25").inOrder()
        assertThat(result.map { it.flightDate.value }).containsExactly("2026-11-21", "2026-11-22", "2026-11-23").inOrder()
        // Overnight arrivals carry their own date (+1d), a same-day arrival its departure date.
        assertThat(result.map { it.arrivalDate.value }).containsExactly("2026-11-22", "2026-11-23", "2026-11-23").inOrder()
        assertThat(result.map { it.flightDate.confidence }.distinct()).containsExactly(ExtractionConfidence.MEDIUM)
        // No passenger, seat, cabin or terminal is invented.
        assertThat(result.flatMap { it.passengers }).isEmpty()
        assertThat(result.none { it.depTerminal.isPresent || it.arrTerminal.isPresent || it.cabinClass.isPresent }).isTrue()
    }

    @Test
    fun `a header that names another leg's flight does not steal the first leg`() {
        val result =
            flights(
                header.replace("AS22", "AI116") + "\n" +
                    legBlock("11:05 PM", "21 Nov 2026", "SEA", "7:24 AM", "JFK", "Alaska Airlines / AS22") +
                    "\nLayover 2h 41m • New York (JFK)\n" +
                    legBlock("10:05 AM", "22 Nov 2026", "JFK", "11:10 AM", "BOM", "Air India / AI116"),
            )

        assertThat(result.map { it.flightKeyForTest() }).containsExactly("AS22", "AI116").inOrder()
        assertThat(result.map { "${it.fromAirport.value}-${it.toAirport.value}" }).containsExactly("SEA-JFK", "JFK-BOM").inOrder()
    }

    @Test
    fun `a plus-one-day marker without its own readable date adds a day to the departure date`() {
        val result =
            flights(
                """
                11:05 PM • 21 Nov 2026
                (SEA) Seattle-Tacoma International Airport
                7:24 AM +1d
                (JFK) John F. Kennedy International Airport
                Airline/Flight #  Alaska Airlines / AS22 Booked
                """,
            ).single()

        assertThat(result.flightDate.value).isEqualTo("2026-11-21")
        assertThat(result.arrivalDate.value).isEqualTo("2026-11-22")
    }

    @Test
    fun `every separator between a stamp's time and date is accepted`() {
        for (separator in listOf(" • ", "• ", " . ", ". ", ", ", " | ", " - ", " ")) {
            val result =
                flights(
                    """
                    2:25 AM${separator}11 Nov 2026
                    (BLR) Kempegowda International Airport
                    8:30 AM${separator}11 Nov 2026
                    (AMS) Amsterdam Airport Schiphol
                    Airline/Flight #  KLM / KL880 Booked
                    """,
                ).single()
            assertThat(result.depTime.value).isEqualTo("02:25")
            assertThat(result.arrTime.value).isEqualTo("08:30")
            assertThat(result.flightDate.value).isEqualTo("2026-11-11")
            assertThat(result.fromAirport.value).isEqualTo("BLR")
            assertThat(result.toAirport.value).isEqualTo("AMS")
        }
    }

    @Test
    fun `layover duration and emission rows are never flights times or locators`() {
        val result =
            flights(
                header.replace("Alaska Airlines  Air India\nAS22", "KLM  KLM\nKL880") + "\n" +
                    legBlock("2:25 AM", "11 Nov 2026", "BLR", "8:30 AM", "AMS", "KLM / KL880") +
                    "\n2 Layover 1h 45m • Amsterdam (AMS)\nCO, emissions  169711 kg\nTravel time: 10h 32m\n" +
                    legBlock("10:15 AM", "11 Nov 2026", "AMS", "11:47 AM", "SEA", "KLM / KL6033"),
            )

        assertThat(result.map { it.flightKeyForTest() }).containsExactly("KL880", "KL6033").inOrder()
        assertThat(result.map { it.toAirport.value }).containsExactly("AMS", "SEA").inOrder()
        assertThat(result.map { it.arrTime.value }).containsExactly("08:30", "11:47").inOrder()
        assertThat(result.map { it.pnr.value }).containsExactly("HVLYKH", "HVLYKH")
    }

    @Test
    fun `the airline confirmation beats the agency confirmation for every leg`() {
        val result =
            flights(
                header + "\n" +
                    legBlock("11:05 PM", "21 Nov 2026", "SEA", "7:24 AM", "JFK", "Alaska Airlines / AS22") +
                    "\n" +
                    legBlock("10:05 AM", "22 Nov 2026", "JFK", "11:10 AM", "BOM", "Air India / AI116"),
            )

        assertThat(result.map { it.pnr.value }).containsExactly("HVLYKH", "HVLYKH")
        assertThat(result.map { it.pnr.confidence }.distinct()).containsExactly(ExtractionConfidence.HIGH)
    }

    @Test
    fun `the agency confirmation is kept at medium confidence when it is the only locator`() {
        val result =
            flights(
                """
                Seattle, WA
                Agency confirmation number:  7E3AR5
                KLM  KLM
                One-way trip
                """ + "\n" +
                    legBlock("2:25 AM", "11 Nov 2026", "BLR", "8:30 AM", "AMS", "KLM / KL880") + "\n" +
                    legBlock("10:15 AM", "11 Nov 2026", "AMS", "11:47 AM", "SEA", "KLM / KL6033"),
            )

        assertThat(result.map { it.pnr.value }).containsExactly("7E3AR5", "7E3AR5")
        assertThat(result.map { it.pnr.confidence }.distinct()).containsExactly(ExtractionConfidence.MEDIUM)
    }

    @Test
    fun `a single airline locator is shared with legs that have none but never overrides a leg's own`() {
        val shared =
            flights(
                """
                Onward 6E 2001 DEL - GOI Date: 10-01-2027
                Return 6E 2002 GOI - DEL Date: 17-01-2027
                Airline confirmation  HVLYKH
                """,
            )
        assertThat(shared.map { it.pnr.value }).containsExactly("HVLYKH", "HVLYKH")

        val perLeg =
            flights(
                """
                Onward 6E 2001 DEL - GOI Date: 10-01-2027 PNR: A1B2C3
                Return 6E 2002 GOI - DEL Date: 17-01-2027 PNR: D4E5F6
                """,
            )
        assertThat(perLeg.map { it.pnr.value }).containsExactly("A1B2C3", "D4E5F6").inOrder()
    }

    @Test
    fun `a leg with a route or times is never dropped as identity-only noise`() {
        val result =
            flights(
                """
                PNR: A1B2C3
                Onward 6E 2001 DEL - GOI Date: 10-01-2027
                Return 6E 2002  Departure 08:30  Arrival 10:05
                """,
            )

        assertThat(result.map { it.flightKeyForTest() }).containsExactly("6E2001", "6E2002").inOrder()
        assertThat(result[1].depTime.value).isEqualTo("08:30")
    }

    @Test
    fun `an OCR'd lowercase L inside a code next to the airline name resolves through the alias`() {
        val result =
            flights(
                legBlock("10:05 AM", "22 Nov 2026", "JFK", "11:10 AM", "BOM", "Air India / Al116"),
            ).single()

        assertThat(result.carrier.value).isEqualTo("AI")
        assertThat(result.flightNumber.value).isEqualTo("116")
    }

    private fun ExtractedFlight.flightKeyForTest(): String = carrier.value + flightNumber.value
}
