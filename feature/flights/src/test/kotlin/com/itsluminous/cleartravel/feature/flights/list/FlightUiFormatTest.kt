package com.itsluminous.cleartravel.feature.flights.list

import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.testing.Fixtures
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * ADR-050 amendment: the pure pieces behind `Arr 07:24 +1` on the card / review sheet
 * and `Scheduled: 07:24 · Sun, 22 Nov` on the detail sheet.
 */
class FlightUiFormatTest {
    private val zone: ZoneId = ZoneId.of("Asia/Kolkata")

    // AS 22: SEA 23:05 Sat 21 Nov 2026 → JFK 07:24 Sun 22 Nov 2026 (device zone Asia/Kolkata).
    private val dep: Instant = ZonedDateTime.of(2026, 11, 21, 23, 5, 0, 0, zone).toInstant()
    private val arr: Instant = ZonedDateTime.of(2026, 11, 22, 7, 24, 0, 0, zone).toInstant()
    private val overnight =
        Fixtures.flightJourney(
            airlineIata = "AS",
            flightNumber = "22",
            date = LocalDate.of(2026, 11, 21),
            depAirport = "SEA",
            arrAirport = "JFK",
            schedDep = dep,
            schedArr = arr,
        )

    @Test
    fun `arrivalDayOffset is 1 for an overnight leg, 0 same day, 2 across two midnights, null without an arrival`() {
        assertThat(arrivalDayOffset(overnight, zone)).isEqualTo(1)
        val sameDay = overnight.copy(schedDep = arr.minusSeconds(2 * 3600), schedArr = arr)
        assertThat(arrivalDayOffset(sameDay, zone)).isEqualTo(0)
        assertThat(arrivalDayOffset(overnight.copy(schedArr = arr.plusSeconds(24 * 3600)), zone)).isEqualTo(2)
        assertThat(arrivalDayOffset(overnight.copy(schedArr = null), zone)).isNull()
    }

    @Test
    fun `arrivalDayOffset reads the LIVE times the card shows and falls back to the header date`() {
        // Estimated arrival slipped past midnight on a same-day leg → the card's `Arr` is now +1.
        val delayed =
            overnight.copy(
                schedDep = ZonedDateTime.of(2026, 11, 21, 20, 0, 0, 0, zone).toInstant(),
                schedArr = ZonedDateTime.of(2026, 11, 21, 23, 30, 0, 0, zone).toInstant(),
                estArr = ZonedDateTime.of(2026, 11, 22, 0, 40, 0, 0, zone).toInstant(),
            )
        assertThat(arrivalDayOffset(delayed, zone)).isEqualTo(1)
        // No departure instant at all: the header date is the baseline.
        assertThat(arrivalDayOffset(overnight.copy(schedDep = null), zone)).isEqualTo(1)
        assertThat(arrivalDayOffset(overnight.copy(schedDep = null, date = null), zone)).isNull()
    }

    @Test
    fun `withDayOffset appends the suffix only for a positive offset`() {
        assertThat(withDayOffset("07:24", 1, "+%d")).isEqualTo("07:24 +1")
        assertThat(withDayOffset("07:24", 2, "+%d")).isEqualTo("07:24 +2")
        assertThat(withDayOffset("07:24", 0, "+%d")).isEqualTo("07:24")
        assertThat(withDayOffset("07:24", null, "+%d")).isEqualTo("07:24")
        assertThat(withDayOffset("07:24", -1, "+%d")).isEqualTo("07:24")
    }

    @Test
    fun `formatDayIfDiffers names the day only when it is not the header day`() {
        val header = LocalDate.of(2026, 11, 21)
        assertThat(formatDayIfDiffers(arr, header, zone)).isEqualTo("Sun, 22 Nov")
        assertThat(formatDayIfDiffers(dep, header, zone)).isNull()
        assertThat(formatDayIfDiffers(null, header, zone)).isNull()
        assertThat(formatDayIfDiffers(arr, null, zone)).isNull()
    }

    @Test
    fun `formatDayIfDiffers reads the day in the requested zone`() {
        // 07:24 IST on the 22nd is still 01:54Z, 21st 17:54 in Los Angeles → same day as a 21 Nov header there.
        assertThat(formatDayIfDiffers(arr, LocalDate.of(2026, 11, 21), ZoneId.of("America/Los_Angeles"))).isNull()
        assertThat(formatTime(arr, zone)).isEqualTo("07:24")
    }
}
