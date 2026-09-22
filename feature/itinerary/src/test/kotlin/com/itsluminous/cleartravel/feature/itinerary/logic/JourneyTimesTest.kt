package com.itsluminous.cleartravel.feature.itinerary.logic

import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.testing.Fixtures
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** ADR-029 part C: what a linked journey contributes as the commute leg's planned time. */
class JourneyTimesTest {
    private val stops =
        listOf(
            Fixtures.trainRouteStop(stationName = "KSR Bengaluru (SBC)", arrival = "", departure = "20:00", sortOrder = 0),
            Fixtures.trainRouteStop(stationName = "Yesvantpur Jn (YPR)", arrival = "20:20", departure = "20:25", sortOrder = 1),
            Fixtures.trainRouteStop(stationName = "New Delhi (NDLS)", arrival = "05:55", departure = "", sortOrder = 2),
        )

    @Test
    fun `train departure matches the boarding station by code suffix`() {
        assertThat(trainDepartureTime(stops, "YPR")).isEqualTo("20:25")
    }

    @Test
    fun `train departure matches the boarding station by name prefix or full name`() {
        assertThat(trainDepartureTime(stops, "Yesvantpur")).isEqualTo("20:25")
        assertThat(trainDepartureTime(stops, "ksr bengaluru (sbc)")).isEqualTo("20:00")
    }

    @Test
    fun `train departure falls back to the first stop when the station is unknown or blank`() {
        assertThat(trainDepartureTime(stops, "MAS")).isEqualTo("20:00")
        assertThat(trainDepartureTime(stops, "")).isEqualTo("20:00")
    }

    @Test
    fun `train departure is null without a route or when the matched stop has none`() {
        assertThat(trainDepartureTime(emptyList(), "SBC")).isNull()
        assertThat(trainDepartureTime(stops, "NDLS")).isNull()
    }

    @Test
    fun `flight departure renders the scheduled departure in the given zone`() {
        val schedDep = Instant.parse("2026-10-02T02:50:00Z")

        assertThat(flightDepartureTime(schedDep, ZoneId.of("Asia/Kolkata"))).isEqualTo("08:20")
        assertThat(flightDepartureTime(schedDep, ZoneOffset.UTC)).isEqualTo("02:50")
        assertThat(flightDepartureTime(null, ZoneOffset.UTC)).isNull()
    }
}
