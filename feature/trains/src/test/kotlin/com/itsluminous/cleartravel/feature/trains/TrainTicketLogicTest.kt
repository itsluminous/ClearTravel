package com.itsluminous.cleartravel.feature.trains

import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.testing.Fixtures
import org.junit.Test
import java.time.LocalDate

class TrainTicketLogicTest {
    // --- isValidPnr ---

    @Test
    fun `ten digits is a valid pnr`() {
        assertThat(isValidPnr("8524317690")).isTrue()
    }

    @Test
    fun `surrounding whitespace is tolerated`() {
        assertThat(isValidPnr(" 8524317690 ")).isTrue()
    }

    @Test
    fun `too short pnr is invalid`() {
        assertThat(isValidPnr("852431769")).isFalse()
    }

    @Test
    fun `too long pnr is invalid`() {
        assertThat(isValidPnr("85243176901")).isFalse()
    }

    @Test
    fun `letters make a pnr invalid`() {
        assertThat(isValidPnr("85243A7690")).isFalse()
    }

    @Test
    fun `blank pnr is invalid`() {
        assertThat(isValidPnr("")).isFalse()
    }

    // --- isPastJourney ---

    private val today = LocalDate.parse("2026-09-20")

    @Test
    fun `journey before today is past`() {
        val ticket = Fixtures.trainTicket(journeyDate = today.minusDays(1))

        assertThat(isPastJourney(ticket, today)).isTrue()
    }

    @Test
    fun `journey today is not past`() {
        val ticket = Fixtures.trainTicket(journeyDate = today)

        assertThat(isPastJourney(ticket, today)).isFalse()
    }

    @Test
    fun `future journey is not past`() {
        val ticket = Fixtures.trainTicket(journeyDate = today.plusDays(3))

        assertThat(isPastJourney(ticket, today)).isFalse()
    }

    @Test
    fun `missing journey date is never past`() {
        val ticket = Fixtures.trainTicket(journeyDate = null)

        assertThat(isPastJourney(ticket, today)).isFalse()
    }

    // --- journeyDuration ---

    @Test
    fun `duration spans days using the running-day offset`() {
        val stops =
            listOf(
                Fixtures.trainRouteStop(stationName = "MMCT", arrival = "", departure = "17:00", day = 1, sortOrder = 0),
                Fixtures.trainRouteStop(stationName = "NDLS", arrival = "08:35", departure = "", day = 2, sortOrder = 1),
            )

        val duration = journeyDuration(stops)

        assertThat(duration).isNotNull()
        assertThat(duration!!.toHours()).isEqualTo(15)
        assertThat(duration.toMinutesPart()).isEqualTo(35)
    }

    @Test
    fun `fewer than two stops has no duration`() {
        val stops = listOf(Fixtures.trainRouteStop())

        assertThat(journeyDuration(stops)).isNull()
    }

    @Test
    fun `unparseable times have no duration`() {
        val stops =
            listOf(
                Fixtures.trainRouteStop(departure = "??", day = 1, sortOrder = 0),
                Fixtures.trainRouteStop(arrival = "08:35", day = 2, sortOrder = 1),
            )

        assertThat(journeyDuration(stops)).isNull()
    }
}
