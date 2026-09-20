package com.itsluminous.cleartravel.feature.itinerary.logic

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate

class DayPaletteTest {
    @Test
    fun `same day always gets the same color`() {
        assertThat(dayColorArgb(3)).isEqualTo(dayColorArgb(3))
    }

    @Test
    fun `palette cycles for large and negative indices without failing`() {
        assertThat(dayColorArgb(8)).isEqualTo(dayColorArgb(0))
        assertThat(dayColorArgb(-1)).isEqualTo(dayColorArgb(7))
    }
}

class LatLngInputTest {
    @Test
    fun `parses a valid pair with whitespace`() {
        assertThat(parseLatLng(" 12.9716 , 77.5946 ")).isEqualTo(MapPoint(12.9716, 77.5946))
    }

    @Test
    fun `rejects garbage and wrong arity`() {
        assertThat(parseLatLng("")).isNull()
        assertThat(parseLatLng("12.9716")).isNull()
        assertThat(parseLatLng("a,b")).isNull()
        assertThat(parseLatLng("1,2,3")).isNull()
    }

    @Test
    fun `rejects out-of-range coordinates`() {
        assertThat(parseLatLng("91, 0")).isNull()
        assertThat(parseLatLng("0, 181")).isNull()
        assertThat(parseLatLng("-90, -180")).isEqualTo(MapPoint(-90.0, -180.0))
    }

    @Test
    fun `format round-trips through parse`() {
        assertThat(parseLatLng(formatLatLng(12.9716, 77.5946)))
            .isEqualTo(MapPoint(12.9716, 77.5946))
    }
}

class TripFormValidationTest {
    @Test
    fun `blank name is rejected`() {
        assertThat(validateTripForm("  ", null, null)).isEqualTo(TripFormError.NAME_REQUIRED)
    }

    @Test
    fun `end date before start date is rejected`() {
        val start = LocalDate.parse("2026-09-24")
        val end = LocalDate.parse("2026-09-20")

        assertThat(validateTripForm("Goa", start, end)).isEqualTo(TripFormError.END_BEFORE_START)
    }

    @Test
    fun `equal dates and open-ended dates pass`() {
        val day = LocalDate.parse("2026-09-20")

        assertThat(validateTripForm("Goa", day, day)).isNull()
        assertThat(validateTripForm("Goa", day, null)).isNull()
        assertThat(validateTripForm("Goa", null, null)).isNull()
    }
}

class MapReadinessTest {
    @Test
    fun `missing play services outranks a missing key`() {
        assertThat(mapUnavailableReason(hasPlayServices = false, hasApiKey = false))
            .isEqualTo(MapUnavailableReason.MISSING_PLAY_SERVICES)
        assertThat(mapUnavailableReason(hasPlayServices = false, hasApiKey = true))
            .isEqualTo(MapUnavailableReason.MISSING_PLAY_SERVICES)
    }

    @Test
    fun `missing key degrades and a full setup renders`() {
        assertThat(mapUnavailableReason(hasPlayServices = true, hasApiKey = false))
            .isEqualTo(MapUnavailableReason.MISSING_API_KEY)
        assertThat(mapUnavailableReason(hasPlayServices = true, hasApiKey = true)).isNull()
    }
}
