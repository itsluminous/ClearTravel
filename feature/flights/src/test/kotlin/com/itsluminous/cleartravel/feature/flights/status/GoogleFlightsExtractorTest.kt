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
 * the `synthetic-*` files are those same DOMs with only the state edited (the recon
 * could not observe a live delayed/cancelled card) and are labelled as such inside.
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
