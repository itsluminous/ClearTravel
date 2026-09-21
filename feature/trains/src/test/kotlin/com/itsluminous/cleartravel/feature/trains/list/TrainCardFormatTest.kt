package com.itsluminous.cleartravel.feature.trains.list

import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.testing.Fixtures
import org.junit.Test
import java.time.Instant

class TrainCardFormatTest {
    private val now = Instant.parse("2026-09-29T08:35:00Z")

    // --- relativeAge ---

    @Test
    fun `under a minute is just now`() {
        assertThat(relativeAge(now.minusSeconds(45), now)).isEqualTo(RelativeAge.JustNow)
    }

    @Test
    fun `future timestamp clamps to just now`() {
        assertThat(relativeAge(now.plusSeconds(600), now)).isEqualTo(RelativeAge.JustNow)
    }

    @Test
    fun `minutes below an hour`() {
        assertThat(relativeAge(now.minusSeconds(59 * 60 + 30), now)).isEqualTo(RelativeAge.Minutes(59))
    }

    @Test
    fun `hours below a day`() {
        assertThat(relativeAge(now.minusSeconds(5 * 3600 + 120), now)).isEqualTo(RelativeAge.Hours(5))
    }

    @Test
    fun `days from a day onwards`() {
        assertThat(relativeAge(now.minusSeconds(3 * 86_400 + 3600), now)).isEqualTo(RelativeAge.Days(3))
    }

    // --- stationCode ---

    @Test
    fun `code in trailing parentheses wins`() {
        assertThat(stationCode("New Delhi (NDLS)")).isEqualTo("NDLS")
    }

    @Test
    fun `bare code is upper-cased as-is`() {
        assertThat(stationCode(" udn ")).isEqualTo("UDN")
    }

    @Test
    fun `plain name without a code stays as text`() {
        assertThat(stationCode("Danapur")).isEqualTo("Danapur")
    }

    // --- passengerPillLabel ---

    @Test
    fun `rac with number normalizes to dash form`() {
        val p = Fixtures.trainPassenger(currentStatus = "RAC 10", bookingStatus = "RAC 20", coach = "", seatBerth = "")
        assertThat(passengerPillLabel(p)).isEqualTo("RAC - 10")
    }

    @Test
    fun `waitlist falls back to booking status when current is blank`() {
        val p = Fixtures.trainPassenger(currentStatus = "", bookingStatus = "WL/45", coach = "", seatBerth = "")
        assertThat(passengerPillLabel(p)).isEqualTo("WL - 45")
    }

    @Test
    fun `confirmed appends coach and seat from the passenger row`() {
        val p = Fixtures.trainPassenger(currentStatus = "CNF", coach = "B4", seatBerth = "32")
        assertThat(passengerPillLabel(p)).isEqualTo("CNF B4-32")
    }

    @Test
    fun `confirmed reads coach and seat from a raw slash status`() {
        val p = Fixtures.trainPassenger(currentStatus = "CNF/B4/32", coach = "", seatBerth = "")
        assertThat(passengerPillLabel(p)).isEqualTo("CNF B4-32")
    }

    @Test
    fun `no status at all yields no pill`() {
        val p = Fixtures.trainPassenger(currentStatus = "", bookingStatus = "", coach = "B4", seatBerth = "32")
        assertThat(passengerPillLabel(p)).isNull()
    }

    @Test
    fun `unknown status shape is shown verbatim`() {
        val p = Fixtures.trainPassenger(currentStatus = "CAN/MOD", bookingStatus = "", coach = "", seatBerth = "")
        assertThat(passengerPillLabel(p)).isEqualTo("CAN/MOD")
    }

    // --- cardTitle / departureTime ---

    @Test
    fun `title joins number and name with a dash and drops blanks`() {
        assertThat(cardTitle(Fixtures.trainTicket(trainNumber = "20933", trainName = "Danapur SF Express")))
            .isEqualTo("20933 - Danapur SF Express")
        assertThat(cardTitle(Fixtures.trainTicket(trainNumber = "12951", trainName = ""))).isEqualTo("12951")
    }

    @Test
    fun `departure time comes from the first stop by sort order`() {
        val stops =
            listOf(
                Fixtures.trainRouteStop(stationName = "B", departure = "10:00", sortOrder = 1),
                Fixtures.trainRouteStop(stationName = "A", departure = "08:35", sortOrder = 0),
            )
        assertThat(departureTime(stops)).isEqualTo("08:35")
        assertThat(departureTime(emptyList())).isNull()
    }
}
