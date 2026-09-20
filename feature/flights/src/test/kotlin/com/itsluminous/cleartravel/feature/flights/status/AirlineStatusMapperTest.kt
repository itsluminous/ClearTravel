package com.itsluminous.cleartravel.feature.flights.status

import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.model.FlightStatus
import com.itsluminous.cleartravel.core.scrape.ScrapedData
import com.itsluminous.cleartravel.core.testing.Fixtures
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class AirlineStatusMapperTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val fetchedAt = Fixtures.NOW
    private val flight =
        Fixtures.flightJourney(
            airlineIata = "AI",
            flightNumber = "101",
            date = LocalDate.parse("2026-09-20"),
            depAirport = "DEL",
            arrAirport = "FCO",
            schedDep = null,
            schedArr = null,
        )

    private fun card(vararg pairs: Pair<String, String>): Map<String, String> = mapOf(*pairs)

    private val delCard =
        card(
            "flightLabel" to "AI 101",
            "status" to "Scheduled",
            "aircraftType" to "Airbus A350-900",
            "depAirport" to "Indira Gandhi International Airport (DEL)",
            "depDate" to "20 Sep 2026",
            "depTimeEst" to "22:55",
            "depTerminalGate" to "Terminal 3 | Gate 24",
            "arrAirport" to "Rome Fiumicino Airport (FCO)",
            "arrDate" to "21 Sep 2026",
            "arrTimeSched" to "04:40",
            "arrTimeEst" to "04:18",
            "arrTerminalGate" to "Terminal 3 | Gate N/A",
        )

    private val fcoCard =
        card(
            "flightLabel" to "AI 101",
            "status" to "Delayed",
            "depAirport" to "Rome Fiumicino Airport (FCO)",
            "depDate" to "21 Sep 2026",
            "depTimeSched" to "06:30",
            "depTimeEst" to "07:10",
            "depTerminalGate" to "Terminal 3 | Gate N/A",
        )

    @Test
    fun `maps a single card to a full result`() {
        val result = AirlineStatusMapper.map(ScrapedData(emptyMap(), rows = listOf(delCard)), flight, fetchedAt, zone)!!

        assertThat(result.status).isEqualTo(FlightStatus.SCHEDULED)
        assertThat(result.aircraftType).isEqualTo("Airbus A350-900")
        assertThat(result.depTerminal).isEqualTo("3")
        assertThat(result.depGate).isEqualTo("24")
        assertThat(result.arrTerminal).isEqualTo("3")
        assertThat(result.arrGate).isEmpty() // "N/A" collapses to unknown
        assertThat(result.schedDep)
            .isEqualTo(ZonedDateTime.of(2026, 9, 20, 22, 55, 0, 0, zone).toInstant())
        // Arrival uses arrDate (next day).
        assertThat(result.schedArr)
            .isEqualTo(ZonedDateTime.of(2026, 9, 21, 4, 40, 0, 0, zone).toInstant())
        assertThat(result.estArr)
            .isEqualTo(ZonedDateTime.of(2026, 9, 21, 4, 18, 0, 0, zone).toInstant())
        assertThat(result.fetchedAt).isEqualTo(fetchedAt)
    }

    @Test
    fun `multi-card results disambiguate by the journey's departure airport`() {
        val data = ScrapedData(emptyMap(), rows = listOf(fcoCard, delCard))

        val forDel = AirlineStatusMapper.map(data, flight, fetchedAt, zone)!!
        assertThat(forDel.status).isEqualTo(FlightStatus.SCHEDULED)

        val forFco = AirlineStatusMapper.map(data, flight.copy(depAirport = "FCO"), fetchedAt, zone)!!
        assertThat(forFco.status).isEqualTo(FlightStatus.DELAYED)
    }

    @Test
    fun `falls back to date matching when airports don't match`() {
        val data = ScrapedData(emptyMap(), rows = listOf(fcoCard, delCard))
        val noAirport = flight.copy(depAirport = "", date = LocalDate.parse("2026-09-21"))

        val result = AirlineStatusMapper.map(data, noAirport, fetchedAt, zone)!!

        assertThat(result.status).isEqualTo(FlightStatus.DELAYED)
    }

    @Test
    fun `falls back to the first card otherwise`() {
        val data = ScrapedData(emptyMap(), rows = listOf(fcoCard, delCard))
        val stranger = flight.copy(depAirport = "JFK", date = LocalDate.parse("2026-12-01"))

        assertThat(AirlineStatusMapper.map(data, stranger, fetchedAt, zone)!!.status)
            .isEqualTo(FlightStatus.DELAYED)
    }

    @Test
    fun `single-value fields work when a rule has no rows`() {
        val data = ScrapedData(fields = card("status" to "Cancelled"))

        assertThat(AirlineStatusMapper.map(data, flight, fetchedAt, zone)!!.status)
            .isEqualTo(FlightStatus.CANCELLED)
    }

    @Test
    fun `status text mapping covers every vocabulary bucket`() {
        assertThat(AirlineStatusMapper.mapStatus("On Time")).isEqualTo(FlightStatus.SCHEDULED)
        assertThat(AirlineStatusMapper.mapStatus("Boarding")).isEqualTo(FlightStatus.BOARDING)
        assertThat(AirlineStatusMapper.mapStatus("Departed")).isEqualTo(FlightStatus.DEPARTED)
        assertThat(AirlineStatusMapper.mapStatus("In Air")).isEqualTo(FlightStatus.DEPARTED)
        assertThat(AirlineStatusMapper.mapStatus("Landed")).isEqualTo(FlightStatus.LANDED)
        assertThat(AirlineStatusMapper.mapStatus("Arrived")).isEqualTo(FlightStatus.LANDED)
        assertThat(AirlineStatusMapper.mapStatus("Delayed 45 min")).isEqualTo(FlightStatus.DELAYED)
        assertThat(AirlineStatusMapper.mapStatus("CANCELLED")).isEqualTo(FlightStatus.CANCELLED)
        assertThat(AirlineStatusMapper.mapStatus("gibberish")).isEqualTo(FlightStatus.UNKNOWN)
        assertThat(AirlineStatusMapper.mapStatus(null)).isEqualTo(FlightStatus.UNKNOWN)
    }

    @Test
    fun `terminal-gate splitting handles odd shapes`() {
        assertThat(AirlineStatusMapper.splitTerminalGate("Terminal 3 | Gate 24")).isEqualTo("3" to "24")
        assertThat(AirlineStatusMapper.splitTerminalGate("Terminal 1D")).isEqualTo("1D" to "")
        assertThat(AirlineStatusMapper.splitTerminalGate("Gate B12")).isEqualTo("" to "B12")
        assertThat(AirlineStatusMapper.splitTerminalGate(null)).isEqualTo("" to "")
        assertThat(AirlineStatusMapper.splitTerminalGate("Terminal N/A | Gate N/A")).isEqualTo("" to "")
    }

    @Test
    fun `unmappable data returns null instead of a fabricated result`() {
        val data = ScrapedData(fields = mapOf("somethingElse" to "value"))

        assertThat(AirlineStatusMapper.map(data, flight, fetchedAt, zone)).isNull()
    }
}
