package com.itsluminous.cleartravel.feature.flights.status

import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.model.FlightStatus
import com.itsluminous.cleartravel.core.testing.Fixtures
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * Fixture tests for the pure Google flight-status card parser (ADR-026). REAL captures
 * (`scheduled-ai101`, `departed-6e2001`) come straight from the 2026-09-22 recon;
 * the `synthetic-*` files are those same DOMs with only the state edited and are
 * labelled as such inside; the `live-*` files are untouched WebView dumps from the
 * Android 16 emulator (2026-09-22 validation run: cancelled, delayed, arrived-late,
 * early, multi-card and wrong-day pages — every state the synthetic files guessed at
 * is now pinned by a real DOM as well).
 */
class GoogleFlightsExtractorTest {
    private val zone = ZoneOffset.UTC
    private val captureDay: LocalDate = LocalDate.of(2026, 9, 22)

    private fun fixture(name: String): String =
        requireNotNull(javaClass.classLoader?.getResourceAsStream("google-flights/$name")) { "missing fixture $name" }
            .use { it.readBytes().toString(Charsets.UTF_8) }

    private fun at(
        date: LocalDate,
        time: String,
    ) = ZonedDateTime.of(date, LocalTime.parse(time), zone).toInstant()

    private val ai101 =
        Fixtures.flightJourney(
            airlineIata = "AI",
            flightNumber = "101",
            date = captureDay,
            depAirport = "DEL",
            arrAirport = "FCO",
            schedDep = null,
            schedArr = null,
        )

    private val indigo2001 =
        Fixtures.flightJourney(
            airlineIata = "6E",
            flightNumber = "2001",
            date = captureDay,
            depAirport = "PAT",
            arrAirport = "DEL",
            schedDep = null,
            schedArr = null,
        )

    @Test
    fun `real scheduled card - AI 101 - maps times terminal and status without a gate`() {
        val result = requireNotNull(GoogleFlightsExtractor.map(fixture("scheduled-ai101.html"), ai101, Fixtures.NOW, zone))

        assertThat(result.status).isEqualTo(FlightStatus.SCHEDULED)
        assertThat(result.schedDep).isEqualTo(at(captureDay, "22:55"))
        // Arrival column says "Rome · Wed, 23 Sept" — next day, 4:40 am.
        assertThat(result.schedArr).isEqualTo(at(captureDay.plusDays(1), "04:40"))
        assertThat(result.estDep).isNull()
        assertThat(result.estArr).isNull()
        assertThat(result.depTerminal).isEqualTo("3")
        assertThat(result.depGate).isEmpty() // "-" placeholder
        assertThat(result.arrTerminal).isEqualTo("3")
        assertThat(result.arrGate).isEmpty()
        assertThat(result.fetchedAt).isEqualTo(Fixtures.NOW)
    }

    @Test
    fun `real departed-early card - 6E 2001 - is DEPARTED with original times as schedule and actuals as estimates`() {
        val result = requireNotNull(GoogleFlightsExtractor.map(fixture("departed-6e2001.html"), indigo2001, Fixtures.NOW, zone))

        assertThat(result.status).isEqualTo(FlightStatus.DEPARTED)
        assertThat(result.schedDep).isEqualTo(at(captureDay, "08:20")) // <del>8:20 am</del>
        assertThat(result.estDep).isEqualTo(at(captureDay, "08:09")) // "Departed 8:09 am"
        assertThat(result.schedArr).isEqualTo(at(captureDay, "10:15"))
        assertThat(result.estArr).isEqualTo(at(captureDay, "09:44"))
        assertThat(result.depTerminal).isEmpty()
        assertThat(result.depGate).isEmpty()
        assertThat(result.arrTerminal).isEqualTo("2")
    }

    @Test
    fun `LIVE device dump - 6E 2001 landed - empty tabpanels, async card container, no-space am-pm`() {
        val result = requireNotNull(GoogleFlightsExtractor.map(fixture("live-landed-6e2001.html"), indigo2001, Fixtures.NOW, zone))

        assertThat(result.status).isEqualTo(FlightStatus.LANDED)
        assertThat(result.schedDep).isEqualTo(at(captureDay, "08:20"))
        assertThat(result.estDep).isEqualTo(at(captureDay, "08:09"))
        assertThat(result.schedArr).isEqualTo(at(captureDay, "10:15"))
        assertThat(result.estArr).isEqualTo(at(captureDay, "09:37"))
        assertThat(result.arrTerminal).isEqualTo("2")
        assertThat(result.depGate).isEmpty()
        val panel = requireNotNull(GoogleFlightsExtractor.parse(fixture("live-landed-6e2001.html")))
        assertThat(panel.flightLabel).isEqualTo("IndiGo 6E 2001")
        assertThat(panel.cards.single().headerStatus).isEqualTo("Arrived")
    }

    @Test
    fun `LIVE cancelled card - SG 128 - CANCELLED with the struck original as the schedule and arrival terminal`() {
        val sg128 = Fixtures.flightJourney(airlineIata = "SG", flightNumber = "128", date = captureDay, depAirport = "", arrAirport = "")

        val result = requireNotNull(GoogleFlightsExtractor.map(fixture("live-cancelled-sg128.html"), sg128, Fixtures.NOW, zone))

        assertThat(result.status).isEqualTo(FlightStatus.CANCELLED)
        // The "Scheduled departure" caption has NO time value on a cancelled card, only <del>6:45 am</del>.
        assertThat(result.schedDep).isEqualTo(at(captureDay, "06:45"))
        assertThat(result.schedArr).isEqualTo(at(captureDay, "08:10"))
        assertThat(result.estDep).isNull()
        assertThat(result.estArr).isNull()
        assertThat(result.depTerminal).isEmpty()
        assertThat(result.arrTerminal).isEqualTo("1D")
        assertThat(result.depGate).isEmpty()
        val panel = requireNotNull(GoogleFlightsExtractor.parse(fixture("live-cancelled-sg128.html")))
        assertThat(panel.flightLabel).isEqualTo("SpiceJet SG 128")
        // The hidden role=dialog duplicate of the card is not a second card.
        assertThat(panel.cards).hasSize(1)
        assertThat(panel.cards.single().headerStatus).isEqualTo("Cancelled")
        assertThat(
            panel.cards
                .single()
                .departure
                ?.time,
        ).isEmpty()
        assertThat(
            panel.cards
                .single()
                .departure
                ?.original,
        ).isEqualTo("6:45 am")
    }

    @Test
    fun `LIVE wrong-day page - bare SG 128 query pre-selected Wed 23 Sept - is refused for the 22 Sept journey`() {
        val sg128 =
            Fixtures.flightJourney(
                airlineIata = "SG",
                flightNumber = "128",
                date = captureDay,
                depAirport = "IXL",
                arrAirport = "DEL",
            )

        assertThat(GoogleFlightsExtractor.map(fixture("live-wrong-day-sg128-bare.html"), sg128, Fixtures.NOW, zone)).isNull()
        // …while the same page IS the right one for a journey on the 23rd.
        val next =
            requireNotNull(
                GoogleFlightsExtractor.map(
                    fixture("live-wrong-day-sg128-bare.html"),
                    sg128.copy(date = captureDay.plusDays(1)),
                    Fixtures.NOW,
                    zone,
                ),
            )
        assertThat(next.status).isEqualTo(FlightStatus.SCHEDULED)
        assertThat(next.schedDep).isEqualTo(at(captureDay.plusDays(1), "06:45"))
        assertThat(next.arrTerminal).isEqualTo("1")
    }

    @Test
    fun `LIVE arrived-late card - 6E 541 runway delay - LANDED with late actuals and gate`() {
        val indigo541 =
            Fixtures.flightJourney(
                airlineIata = "6E",
                flightNumber = "541",
                date = captureDay,
                depAirport = "BLR",
                arrAirport = "IXE",
            )

        val result = requireNotNull(GoogleFlightsExtractor.map(fixture("live-arrived-late-6e541.html"), indigo541, Fixtures.NOW, zone))

        assertThat(result.status).isEqualTo(FlightStatus.LANDED)
        assertThat(result.schedDep).isEqualTo(at(captureDay, "07:15"))
        assertThat(result.estDep).isEqualTo(at(captureDay, "08:22"))
        assertThat(result.schedArr).isEqualTo(at(captureDay, "08:15"))
        assertThat(result.estArr).isEqualTo(at(captureDay, "09:18"))
        assertThat(result.depTerminal).isEqualTo("1")
        assertThat(result.depGate).isEqualTo("28")
        assertThat(result.arrTerminal).isEmpty()
        assertThat(requireNotNull(GoogleFlightsExtractor.parse(fixture("live-arrived-late-6e541.html"))).cards.single().headerStatus)
            .isEqualTo("Arrived late")
    }

    @Test
    fun `LIVE early-departure card - 6E 6353 - LANDED, the earlier actual is not a delay and the sign is kept`() {
        val indigo6353 =
            Fixtures.flightJourney(
                airlineIata = "6E",
                flightNumber = "6353",
                date = captureDay,
                depAirport = "BLR",
                arrAirport = "LKO",
            )

        val result = requireNotNull(GoogleFlightsExtractor.map(fixture("live-early-6e6353.html"), indigo6353, Fixtures.NOW, zone))

        assertThat(result.status).isEqualTo(FlightStatus.LANDED)
        assertThat(result.schedDep).isEqualTo(at(captureDay, "07:10"))
        assertThat(result.estDep).isEqualTo(at(captureDay, "07:03")) // 7 min EARLY
        assertThat(result.estDep).isLessThan(result.schedDep)
        assertThat(result.schedArr).isEqualTo(at(captureDay, "09:50"))
        assertThat(result.estArr).isEqualTo(at(captureDay, "09:27"))
        assertThat(result.depTerminal).isEqualTo("1")
        assertThat(result.depGate).isEqualTo("22")
        assertThat(result.arrTerminal).isEqualTo("3")
    }

    @Test
    fun `LIVE departing-late card - 6E 6144 - DELAYED from the header wording with estimates and gate`() {
        val indigo6144 =
            Fixtures.flightJourney(
                airlineIata = "6E",
                flightNumber = "6144",
                date = captureDay,
                depAirport = "BLR",
                arrAirport = "TRV",
            )

        val result = requireNotNull(GoogleFlightsExtractor.map(fixture("live-departing-late-6e6144.html"), indigo6144, Fixtures.NOW, zone))

        assertThat(result.status).isEqualTo(FlightStatus.DELAYED)
        assertThat(result.schedDep).isEqualTo(at(captureDay, "10:30"))
        assertThat(result.estDep).isEqualTo(at(captureDay, "11:40"))
        assertThat(result.schedArr).isEqualTo(at(captureDay, "11:50"))
        assertThat(result.estArr).isEqualTo(at(captureDay, "13:00"))
        assertThat(result.depTerminal).isEqualTo("1")
        assertThat(result.depGate).isEqualTo("25")
        assertThat(result.arrTerminal).isEqualTo("1")
        assertThat(requireNotNull(GoogleFlightsExtractor.parse(fixture("live-departing-late-6e6144.html"))).cards.single().headerStatus)
            .isEqualTo("Departing late")
    }

    @Test
    fun `LIVE multi-card page - 6E 9468 two legs on one date - the journey's airports pick the leg`() {
        val html = fixture("live-multi-card-6e9468.html")
        val panel = requireNotNull(GoogleFlightsExtractor.parse(html))
        assertThat(panel.cards.map { it.originCode to it.destCode }).containsExactly("AUH" to "BLR", "BLR" to "IXE").inOrder()
        assertThat(panel.cards.map { it.headerStatus }).containsExactly("Diverted", "Arrived late").inOrder()

        val base = Fixtures.flightJourney(airlineIata = "6E", flightNumber = "9468", date = captureDay, depAirport = "", arrAirport = "")

        // Second leg by departure airport.
        val blrIxe = requireNotNull(GoogleFlightsExtractor.map(html, base.copy(depAirport = "BLR", arrAirport = "IXE"), Fixtures.NOW, zone))
        assertThat(blrIxe.status).isEqualTo(FlightStatus.LANDED)
        assertThat(blrIxe.schedDep).isEqualTo(at(captureDay, "07:35"))
        assertThat(blrIxe.estDep).isEqualTo(at(captureDay, "08:30"))
        assertThat(blrIxe.estArr).isEqualTo(at(captureDay, "09:29"))
        assertThat(blrIxe.depTerminal).isEqualTo("2")

        // Second leg by arrival airport alone.
        assertThat(
            requireNotNull(GoogleFlightsExtractor.map(html, base.copy(arrAirport = "ixe"), Fixtures.NOW, zone)).depTerminal,
        ).isEqualTo("2")

        // First leg (diverted, landed 6:26 am) by departure airport.
        val auhBlr = requireNotNull(GoogleFlightsExtractor.map(html, base.copy(depAirport = "AUH"), Fixtures.NOW, zone))
        assertThat(auhBlr.status).isEqualTo(FlightStatus.LANDED)
        assertThat(auhBlr.schedDep).isEqualTo(at(captureDay, "00:30"))
        assertThat(auhBlr.estDep).isEqualTo(at(captureDay, "00:45"))
        assertThat(auhBlr.estArr).isEqualTo(at(captureDay, "06:26"))
        assertThat(auhBlr.depTerminal).isEqualTo("A")

        // No airports saved: the scheduled departure time breaks the tie …
        assertThat(
            requireNotNull(GoogleFlightsExtractor.map(html, base.copy(schedDep = at(captureDay, "07:35")), Fixtures.NOW, zone)).depTerminal,
        ).isEqualTo("2")
        // … and with nothing at all the first card wins.
        assertThat(requireNotNull(GoogleFlightsExtractor.map(html, base, Fixtures.NOW, zone)).depTerminal).isEqualTo("A")
    }

    @Test
    fun `synthetic delayed card is DELAYED with the later estimate and assigned gate`() {
        val result = requireNotNull(GoogleFlightsExtractor.map(fixture("synthetic-delayed.html"), indigo2001, Fixtures.NOW, zone))

        assertThat(result.status).isEqualTo(FlightStatus.DELAYED)
        assertThat(result.schedDep).isEqualTo(at(captureDay, "08:20"))
        assertThat(result.estDep).isEqualTo(at(captureDay, "09:05"))
        assertThat(result.estArr).isEqualTo(at(captureDay, "11:00"))
        assertThat(result.depTerminal).isEqualTo("1D")
        assertThat(result.depGate).isEqualTo("12")
    }

    @Test
    fun `synthetic cancelled card is CANCELLED`() {
        val result = requireNotNull(GoogleFlightsExtractor.map(fixture("synthetic-cancelled.html"), ai101, Fixtures.NOW, zone))

        assertThat(result.status).isEqualTo(FlightStatus.CANCELLED)
        assertThat(result.schedDep).isEqualTo(at(captureDay, "22:55"))
    }

    @Test
    fun `only the aria-selected tab's panel is read - decoy panel for another day is ignored`() {
        val result = requireNotNull(GoogleFlightsExtractor.map(fixture("synthetic-multi-panel.html"), indigo2001, Fixtures.NOW, zone))

        assertThat(result.status).isEqualTo(FlightStatus.DEPARTED)
        assertThat(result.estDep).isEqualTo(at(captureDay, "08:09"))
        assertThat(result.depGate).isEmpty() // decoy panel carried gate 99 / Landed
        assertThat(result.depTerminal).isEmpty()
    }

    @Test
    fun `card for another day than the saved flight is refused`() {
        val tomorrow = indigo2001.copy(date = captureDay.plusDays(1))

        assertThat(GoogleFlightsExtractor.map(fixture("departed-6e2001.html"), tomorrow, Fixtures.NOW, zone)).isNull()
    }

    @Test
    fun `undated flight is never mapped`() {
        assertThat(GoogleFlightsExtractor.map(fixture("departed-6e2001.html"), indigo2001.copy(date = null), Fixtures.NOW, zone)).isNull()
    }

    @Test
    fun `no rich card on the page yields null`() {
        assertThat(GoogleFlightsExtractor.map(fixture("no-panel.html"), indigo2001, Fixtures.NOW, zone)).isNull()
        assertThat(GoogleFlightsExtractor.parse(fixture("no-panel.html"))).isNull()
    }

    @Test
    fun `garbage input never throws`() {
        for (garbage in listOf("", "not html", "<<<%%%>>>", "<html><body><h2>Flight status</h2></body></html>")) {
            assertThat(GoogleFlightsExtractor.map(garbage, indigo2001, Fixtures.NOW, zone)).isNull()
        }
    }

    @Test
    fun `parse exposes the structured card - header status airports labels and captions`() {
        val panel = requireNotNull(GoogleFlightsExtractor.parse(fixture("departed-6e2001.html")))

        assertThat(panel.flightLabel).isEqualTo("IndiGo 6E 2001")
        assertThat(panel.selectedDate).isEqualTo("Tue, 22 Sept")
        val card = panel.cards.single()
        assertThat(card.headerStatus).isEqualTo("On time")
        assertThat(card.originCode).isEqualTo("PAT")
        assertThat(card.destCode).isEqualTo("DEL")
        val dep = requireNotNull(card.departure)
        assertThat(dep.label).isEqualTo("Departed")
        assertThat(dep.time).isEqualTo("8:09 am")
        assertThat(dep.original).isEqualTo("8:20 am")
        assertThat(dep.terminal).isEqualTo("-")
        assertThat(dep.cityDate).isEqualTo("Patna · Tue, 22 Sept")
        val arr = requireNotNull(card.arrival)
        assertThat(arr.label).isEqualTo("Estimated arrival")
        assertThat(arr.original).isEqualTo("10:15 am")
        assertThat(arr.terminal).isEqualTo("2")
    }

    @Test
    fun `card selection prefers the journey's departure airport then arrival airport`() {
        val del = GoogleFlightsExtractor.Card("h", "Scheduled", originCode = "DEL", destCode = "FCO", departure = null, arrival = null)
        val fco = GoogleFlightsExtractor.Card("h", "Scheduled", originCode = "FCO", destCode = "JFK", departure = null, arrival = null)

        assertThat(GoogleFlightsExtractor.pickCard(listOf(del, fco), ai101.copy(depAirport = "fco", arrAirport = ""))).isSameInstanceAs(fco)
        assertThat(GoogleFlightsExtractor.pickCard(listOf(del, fco), ai101.copy(depAirport = "", arrAirport = "JFK"))).isSameInstanceAs(fco)
        assertThat(GoogleFlightsExtractor.pickCard(listOf(del, fco), ai101.copy(depAirport = "", arrAirport = ""))).isSameInstanceAs(del)
        assertThat(GoogleFlightsExtractor.pickCard(emptyList(), ai101)).isNull()
    }

    @Test
    fun `card selection filters by the journey's date before airports and refuses a page with only other days`() {
        fun side(cityDate: String) = GoogleFlightsExtractor.Side("Scheduled departure", "6:45 am", "", "-", "-", cityDate)
        val today =
            GoogleFlightsExtractor.Card(
                "h",
                "Cancelled",
                originCode = "IXL",
                destCode = "DEL",
                departure = side("Leh · Tue, 22 Sept"),
                arrival = null,
            )
        val tomorrow =
            GoogleFlightsExtractor.Card(
                "h",
                "Scheduled",
                originCode = "IXL",
                destCode = "DEL",
                departure = side("Leh · Wed, 23 Sept"),
                arrival = null,
            )
        val sg128 = ai101.copy(airlineIata = "SG", flightNumber = "128", depAirport = "IXL", arrAirport = "DEL")

        // Same airports on both cards: the date decides, whatever the order.
        assertThat(GoogleFlightsExtractor.pickCard(listOf(tomorrow, today), sg128)).isSameInstanceAs(today)
        assertThat(
            GoogleFlightsExtractor.pickCard(listOf(tomorrow, today), sg128.copy(date = captureDay.plusDays(1))),
        ).isSameInstanceAs(tomorrow)
        // Only another day's card on the page → nothing to pick (the caller keeps the raw page).
        assertThat(GoogleFlightsExtractor.pickCard(listOf(tomorrow), sg128)).isNull()
        // Cards without any date caption are still considered (recon shape) — the tab guard runs later.
        val undated = GoogleFlightsExtractor.Card("h", "Scheduled", originCode = "IXL", destCode = "DEL", departure = null, arrival = null)
        assertThat(GoogleFlightsExtractor.pickCard(listOf(undated), sg128)).isSameInstanceAs(undated)
    }

    @Test
    fun `status vocabulary - header and caption combinations`() {
        fun card(
            header: String,
            depLabel: String = "Scheduled departure",
            arrLabel: String = "Scheduled arrival",
        ) = GoogleFlightsExtractor.Card(
            headerText = header,
            headerStatus = header,
            originCode = "PAT",
            destCode = "DEL",
            departure = GoogleFlightsExtractor.Side(depLabel, "8:20 am", "", "-", "-", ""),
            arrival = GoogleFlightsExtractor.Side(arrLabel, "10:15 am", "", "-", "-", ""),
        )
        val eight20 = LocalTime.of(8, 20)

        assertThat(GoogleFlightsExtractor.deriveStatus(card("Scheduled"), eight20, null)).isEqualTo(FlightStatus.SCHEDULED)
        assertThat(GoogleFlightsExtractor.deriveStatus(card("On time"), eight20, null)).isEqualTo(FlightStatus.SCHEDULED)
        assertThat(GoogleFlightsExtractor.deriveStatus(card("Departing on time"), eight20, null)).isEqualTo(FlightStatus.SCHEDULED)
        assertThat(GoogleFlightsExtractor.deriveStatus(card("On time", depLabel = "Departed"), eight20, null))
            .isEqualTo(FlightStatus.DEPARTED)
        assertThat(GoogleFlightsExtractor.deriveStatus(card("On time", arrLabel = "Landed"), eight20, null))
            .isEqualTo(FlightStatus.LANDED)
        assertThat(GoogleFlightsExtractor.deriveStatus(card("Delayed 20 min"), eight20, null)).isEqualTo(FlightStatus.DELAYED)
        assertThat(GoogleFlightsExtractor.deriveStatus(card("Cancelled"), eight20, null)).isEqualTo(FlightStatus.CANCELLED)
        // Live wording 2026-09-22.
        assertThat(GoogleFlightsExtractor.deriveStatus(card("Departing late", depLabel = "Estimated departure"), eight20, eight20))
            .isEqualTo(FlightStatus.DELAYED)
        assertThat(GoogleFlightsExtractor.deriveStatus(card("Delayed by 1h 40m"), eight20, null)).isEqualTo(FlightStatus.DELAYED)
        assertThat(GoogleFlightsExtractor.deriveStatus(card("Arrived late", depLabel = "Departed", arrLabel = "Arrived"), eight20, null))
            .isEqualTo(FlightStatus.LANDED)
        assertThat(GoogleFlightsExtractor.deriveStatus(card("Arrived"), eight20, null)).isEqualTo(FlightStatus.LANDED)
        assertThat(GoogleFlightsExtractor.deriveStatus(card("Diverted", depLabel = "Departed", arrLabel = "Landed"), eight20, null))
            .isEqualTo(FlightStatus.LANDED)
        assertThat(
            GoogleFlightsExtractor.deriveStatus(card("Diverted", depLabel = "Departed", arrLabel = "Estimated arrival"), eight20, null),
        ).isEqualTo(FlightStatus.DEPARTED)
        // A cancelled card carries no header status on its own but the word still wins over everything.
        assertThat(GoogleFlightsExtractor.deriveStatus(card("Cancelled", depLabel = "Departed", arrLabel = "Landed"), eight20, null))
            .isEqualTo(FlightStatus.CANCELLED)
        // No explicit word but the shown departure is LATER than the struck original → delayed.
        assertThat(GoogleFlightsExtractor.deriveStatus(card("On time", depLabel = "Estimated departure"), LocalTime.of(9, 5), eight20))
            .isEqualTo(FlightStatus.DELAYED)
        // Running EARLY (6E 2001 shape) is not a delay.
        assertThat(GoogleFlightsExtractor.deriveStatus(card("On time", depLabel = "Estimated departure"), LocalTime.of(8, 9), eight20))
            .isEqualTo(FlightStatus.SCHEDULED)
        // Midnight wrap: 23:50 original, 00:20 shown → later.
        assertThat(
            GoogleFlightsExtractor.deriveStatus(card("", depLabel = "Estimated departure"), LocalTime.of(0, 20), LocalTime.of(23, 50)),
        ).isEqualTo(FlightStatus.DELAYED)
        assertThat(GoogleFlightsExtractor.deriveStatus(card("", depLabel = ""), null, null)).isEqualTo(FlightStatus.UNKNOWN)
    }

    @Test
    fun `date captions resolve to the year nearest the journey - including a New Year wrap`() {
        assertThat(GoogleFlightsExtractor.resolveDate("Patna · Tue, 22 Sept", captureDay)).isEqualTo(captureDay)
        assertThat(GoogleFlightsExtractor.resolveDate("Wed, 23 Sept", captureDay)).isEqualTo(captureDay.plusDays(1))
        assertThat(GoogleFlightsExtractor.resolveDate("Rome · Thu, 1 Jan", LocalDate.of(2026, 12, 31))).isEqualTo(LocalDate.of(2027, 1, 1))
        assertThat(GoogleFlightsExtractor.resolveDate("New York · 31 Dec", LocalDate.of(2027, 1, 1))).isEqualTo(LocalDate.of(2026, 12, 31))
        assertThat(GoogleFlightsExtractor.resolveDate("no date here", captureDay)).isNull()
        assertThat(GoogleFlightsExtractor.resolveDate(null, captureDay)).isNull()
    }

    @Test
    fun `times parse 12h with narrow spaces and 24h`() {
        assertThat(GoogleFlightsExtractor.parseTime("8:09 am")).isEqualTo(LocalTime.of(8, 9))
        assertThat(GoogleFlightsExtractor.parseTime("10:55\u202Fpm")).isEqualTo(LocalTime.of(22, 55))
        assertThat(GoogleFlightsExtractor.parseTime("12:05 AM")).isEqualTo(LocalTime.of(0, 5))
        assertThat(GoogleFlightsExtractor.parseTime("22:55")).isEqualTo(LocalTime.of(22, 55))
        assertThat(GoogleFlightsExtractor.parseTime("-")).isNull()
        assertThat(GoogleFlightsExtractor.parseTime(null)).isNull()
    }
}
