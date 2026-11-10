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

    @Test
    fun `dayOffset is zero on the same calendar day`() {
        val dep = Instant.parse("2026-11-23T10:05:00Z")
        val arr = Instant.parse("2026-11-23T11:55:00Z")
        assertThat(DateFormats.dayOffset(dep, arr, ZoneOffset.UTC)).isEqualTo(0)
    }

    @Test
    fun `dayOffset is one for an overnight leg and two across two midnights`() {
        // AS 22: SEA 23:05 Sat 21 Nov → JFK 07:24 Sun 22 Nov (Asia/Kolkata device zone, ADR-050).
        val kolkata = ZoneOffset.ofHoursMinutes(5, 30)
        val dep = Instant.ofEpochSecond(1_795_282_500)
        val arr = Instant.ofEpochSecond(1_795_312_440)
        assertThat(DateFormats.dayOffset(dep, arr, kolkata)).isEqualTo(1)
        assertThat(DateFormats.dayOffset(dep, arr.plusSeconds(24 * 3600), kolkata)).isEqualTo(2)
    }

    @Test
    fun `dayOffset reads the calendar day in the given zone`() {
        // 23:30Z → 01:30Z: a new day in UTC, still the same day at UTC-8 (16:30 → 18:30 on the 12th).
        val dep = Instant.parse("2031-03-12T23:30:00Z")
        val arr = Instant.parse("2031-03-13T01:30:00Z")
        assertThat(DateFormats.dayOffset(dep, arr, ZoneOffset.UTC)).isEqualTo(1)
        assertThat(DateFormats.dayOffset(dep, arr, ZoneOffset.ofHours(-8))).isEqualTo(0)
    }

    @Test
    fun `dayOffset is null when either side is unknown and negative when the end is earlier`() {
        val dep = Instant.parse("2031-03-12T23:30:00Z")
        assertThat(DateFormats.dayOffset(dep, null, ZoneOffset.UTC)).isNull()
        assertThat(DateFormats.dayOffset(null, dep, ZoneOffset.UTC)).isNull()
        assertThat(DateFormats.dayOffset(LocalDate.of(2031, 3, 13), LocalDate.of(2031, 3, 12))).isEqualTo(-1)
    }
}
