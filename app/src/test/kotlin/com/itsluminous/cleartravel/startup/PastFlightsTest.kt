package com.itsluminous.cleartravel.startup

import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.model.FlightJourney
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class PastFlightsTest {
    private val today = LocalDate.of(2026, 9, 20)

    private fun flight(
        date: LocalDate? = null,
        schedDep: Instant? = null,
    ) = FlightJourney(airlineIata = "6E", flightNumber = "2345", date = date, schedDep = schedDep)

    @Test
    fun `flight day before today is past`() {
        assertThat(isPastFlight(flight(date = today.minusDays(1)), today)).isTrue()
    }

    @Test
    fun `flight on the journey day itself is NOT past — user may still be travelling`() {
        assertThat(isPastFlight(flight(date = today), today)).isFalse()
    }

    @Test
    fun `future flight is not past`() {
        assertThat(isPastFlight(flight(date = today.plusDays(2)), today)).isFalse()
    }

    @Test
    fun `missing date falls back to scheduled departure's local date`() {
        val pastDep = today.minusDays(3).atStartOfDay().toInstant(ZoneOffset.UTC)
        assertThat(
            isPastFlight(flight(schedDep = pastDep), today, zone = ZoneOffset.UTC),
        ).isTrue()
    }

    @Test
    fun `no date and no departure is never past`() {
        assertThat(isPastFlight(flight(), today)).isFalse()
    }
}
