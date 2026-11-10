package com.itsluminous.cleartravel.core.designsystem

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit

/**
 * The app-wide LOCALIZED date/time rendering used wherever a screen shows a plain
 * calendar day or a "when did this happen" stamp — journey dates, trip dates, document
 * expiry, backup timestamps, "route fetched", "status checked". One definition so the
 * tabs agree; feature-specific compact patterns (train card band, flight card) stay in
 * their features on purpose. Pure JVM; the zone is a parameter for tests.
 */
object DateFormats {
    private val DATE_MEDIUM: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
    private val TIMESTAMP_MEDIUM_SHORT: DateTimeFormatter =
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)

    /** Medium locale date, e.g. `12 Mar 2031` / `Mar 12, 2031`. */
    fun formatDate(date: LocalDate): String = DATE_MEDIUM.format(date)

    /** Medium date + short time in [zone] (device zone by default), e.g. `12 Mar 2031, 14:05`. */
    fun formatTimestamp(
        instant: Instant,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String = TIMESTAMP_MEDIUM_SHORT.format(instant.atZone(zone))

    /**
     * Calendar days from [from] to [to]: 0 on the same day, 1 for an overnight flight or
     * train leg, 2 for a journey that spans two midnights, negative when [to] is earlier;
     * null when either side is unknown. Shared by the flight surfaces that print `+1`
     * after an arrival time (ADR-050 amendment) and open to the train route page
     * (multi-day rakes) — ADR-036 keeps date helpers two features need here.
     */
    fun dayOffset(
        from: LocalDate?,
        to: LocalDate?,
    ): Int? {
        if (from == null || to == null) return null
        return ChronoUnit.DAYS.between(from, to).toInt()
    }

    /** [dayOffset] of two instants read as calendar days in [zone] (device zone by default). */
    fun dayOffset(
        from: Instant?,
        to: Instant?,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Int? = dayOffset(from?.atZone(zone)?.toLocalDate(), to?.atZone(zone)?.toLocalDate())
}
