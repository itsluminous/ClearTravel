package com.itsluminous.cleartravel.feature.flights.checkin

import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.model.FlightStatus
import com.itsluminous.cleartravel.core.testing.Fixtures
import org.junit.Test
import java.time.Duration
import java.time.Instant

/** ADR-039 part A: the card's web check-in gate (before window / open / closed / departed). */
class CheckInGateTest {
    private val rules =
        CheckInRules(
            version = 1,
            defaultWindow = CheckInWindowSpec(opensHoursBefore = 48, closesHoursBefore = 1),
            airlines =
                mapOf(
                    "AI" to
                        AirlineCheckInInfo(
                            "Air India",
                            opensHoursBefore = 48,
                            closesHoursBefore = 2,
                            checkInUrl = "https://ai/checkin",
                        ),
                ),
        )
    private val departure: Instant = Instant.parse("2026-09-24T06:10:00Z")
    private val flight = Fixtures.flightJourney(airlineIata = "AI", schedDep = departure, checkInUrl = "https://ai/checkin")
    private val fallback = "https://search/checkin"

    private fun decide(
        now: Instant,
        f: com.itsluminous.cleartravel.core.model.FlightJourney = flight,
    ) = decideCheckInGate(f, rules, now, fallback)

    @Test
    fun `before the window reports when check-in opens`() {
        val now = departure.minus(Duration.ofHours(60))

        assertThat(decide(now)).isEqualTo(CheckInGateDecision.NotYetOpen(opensAt = departure.minus(Duration.ofHours(48))))
    }

    @Test
    fun `inside the window opens the airline url - open edge inclusive`() {
        assertThat(decide(departure.minus(Duration.ofHours(48)))).isEqualTo(CheckInGateDecision.Open("https://ai/checkin"))
        assertThat(decide(departure.minus(Duration.ofHours(5)))).isEqualTo(CheckInGateDecision.Open("https://ai/checkin"))
    }

    @Test
    fun `after the airline close offset but before departure is closed`() {
        val closesAt = departure.minus(Duration.ofHours(2))

        assertThat(decide(closesAt)).isEqualTo(CheckInGateDecision.Closed(closedAt = closesAt))
        assertThat(decide(departure.minus(Duration.ofMinutes(30)))).isEqualTo(CheckInGateDecision.Closed(closedAt = closesAt))
    }

    @Test
    fun `past departure is departed regardless of status`() {
        assertThat(decide(departure)).isEqualTo(CheckInGateDecision.Departed)
        assertThat(decide(departure.plus(Duration.ofDays(2)))).isEqualTo(CheckInGateDecision.Departed)
    }

    @Test
    fun `departed or landed status wins even when the clock is early`() {
        val early = departure.minus(Duration.ofHours(10))

        assertThat(decide(early, flight.copy(status = FlightStatus.DEPARTED))).isEqualTo(CheckInGateDecision.Departed)
        assertThat(decide(early, flight.copy(status = FlightStatus.LANDED))).isEqualTo(CheckInGateDecision.Departed)
    }

    @Test
    fun `estimated departure is what departed is judged against`() {
        val delayed = flight.copy(estDep = departure.plus(Duration.ofHours(3)), status = FlightStatus.DELAYED)

        // Past the scheduled time but before the new estimate → window (closed) rather than departed.
        assertThat(decide(departure.plus(Duration.ofHours(1)), delayed))
            .isEqualTo(CheckInGateDecision.Closed(closedAt = departure.minus(Duration.ofHours(2))))
    }

    @Test
    fun `no scheduled departure cannot be gated - opens the url as before`() {
        val undated = flight.copy(schedDep = null, estDep = null)

        assertThat(decide(Instant.parse("2020-01-01T00:00:00Z"), undated)).isEqualTo(CheckInGateDecision.Open("https://ai/checkin"))
    }

    @Test
    fun `unknown airline uses the default window and the fallback url`() {
        val other = flight.copy(airlineIata = "ZZ", checkInUrl = null)

        assertThat(decide(departure.minus(Duration.ofHours(49)), other))
            .isEqualTo(CheckInGateDecision.NotYetOpen(opensAt = departure.minus(Duration.ofHours(48))))
        assertThat(decide(departure.minus(Duration.ofMinutes(90)), other)).isEqualTo(CheckInGateDecision.Open(fallback))
        assertThat(decide(departure.minus(Duration.ofMinutes(59)), other))
            .isEqualTo(CheckInGateDecision.Closed(closedAt = departure.minus(Duration.ofHours(1))))
    }
}
