package com.itsluminous.cleartravel.core.designsystem

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

class DateFormatsTest {
    @Test
    fun `formatDate is the medium localized date`() {
        val day = LocalDate.of(2031, 3, 12)
        assertThat(DateFormats.formatDate(day)).isEqualTo(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(day))
        assertThat(DateFormats.formatDate(day)).contains("2031")
    }

    @Test
    fun `formatTimestamp renders in the requested zone`() {
        val instant = Instant.parse("2031-03-12T23:30:00Z")
        val utc = DateFormats.formatTimestamp(instant, ZoneOffset.UTC)
        val plusFive = DateFormats.formatTimestamp(instant, ZoneOffset.ofHoursMinutes(5, 30))
        assertThat(utc).contains("2031")
        // 23:30Z is already 13 Mar at +05:30 — the zone must move the day.
        assertThat(plusFive).isNotEqualTo(utc)
        assertThat(plusFive).contains("13")
    }
}
