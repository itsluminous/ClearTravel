package com.itsluminous.cleartravel.feature.trains.list

import com.itsluminous.cleartravel.core.model.TrainPassenger
import com.itsluminous.cleartravel.core.model.TrainRouteStop
import com.itsluminous.cleartravel.core.model.TrainTicket
import java.time.Duration
import java.time.Instant

// Pure formatting logic behind the redesigned train card (and its shareable twin):
// station codes for the header band, the relative "Updated X ago" freshness line
// and the per-passenger status pills. No Android, plain-JUnit testable.

/** `Name (CODE)` — the shape IRCTC tickets and our OCR extractor produce. */
private val CODE_IN_PARENS = Regex("""\(([A-Za-z]{2,5})\)\s*$""")

/** A bare station code, e.g. `NDLS`, `SBC`. */
private val BARE_CODE = Regex("""^[A-Za-z]{2,5}$""")

/** `RAC 10`, `RAC/10`, `WL-45`, `PQWL 3`, `RAC10` → (prefix, number). */
private val STATUS_WITH_NUMBER = Regex("""^([A-Za-z]+)\s*[-/ ]?\s*(\d+)\b.*$""")

/** `CNF/B4/32`, `CNF B4 32` → (coach, seat) when the raw status carries them. */
private val CNF_WITH_SEAT = Regex("""^CNF[\s/,-]+([A-Za-z]{1,3}\d{1,2})[\s/,-]+(\d{1,3}[A-Za-z ]*)$""", RegexOption.IGNORE_CASE)

private const val CONFIRMED_PREFIX = "CNF"

/**
 * Station text shortened for the header band: the code inside trailing parentheses
 * (`New Delhi (NDLS)` → `NDLS`), a bare code as-is, otherwise the trimmed text.
 */
fun stationCode(text: String): String {
    val trimmed = text.trim()
    CODE_IN_PARENS.find(trimmed)?.let { return it.groupValues[1].uppercase() }
    if (BARE_CODE.matches(trimmed)) return trimmed.uppercase()
    return trimmed
}

/** Card title: `12951 - Mumbai Rajdhani`; degrades to whichever half is known. */
fun cardTitle(ticket: TrainTicket): String =
    listOf(ticket.trainNumber, ticket.trainName)
        .map(String::trim)
        .filter(String::isNotBlank)
        .joinToString(" - ")

/** Scheduled departure from the FIRST route stop, or null when no stop has one. */
fun departureTime(stops: List<TrainRouteStop>): String? =
    stops
        .sortedBy(TrainRouteStop::sortOrder)
        .firstOrNull()
        ?.departure
        ?.trim()
        ?.takeIf(String::isNotBlank)

/** Coarse relative age of a timestamp — rendered as "Updated X ago" on the card. */
sealed interface RelativeAge {
    data object JustNow : RelativeAge

    data class Minutes(
        val value: Long,
    ) : RelativeAge

    data class Hours(
        val value: Long,
    ) : RelativeAge

    data class Days(
        val value: Long,
    ) : RelativeAge
}

private const val JUST_NOW_SECONDS = 60L
private const val MINUTES_PER_HOUR = 60L
private const val HOURS_PER_DAY = 24L

/**
 * How long ago [then] was relative to [now]: under a minute → [RelativeAge.JustNow],
 * then minutes, hours, days (floored). A [then] in the future clamps to just-now —
 * clock skew must never render "Updated -3 minutes ago".
 */
fun relativeAge(
    then: Instant,
    now: Instant,
): RelativeAge {
    val elapsed = Duration.between(then, now)
    if (elapsed.isNegative || elapsed.seconds < JUST_NOW_SECONDS) return RelativeAge.JustNow
    val minutes = elapsed.toMinutes()
    if (minutes < MINUTES_PER_HOUR) return RelativeAge.Minutes(minutes)
    val hours = elapsed.toHours()
    if (hours < HOURS_PER_DAY) return RelativeAge.Hours(hours)
    return RelativeAge.Days(elapsed.toDays())
}

/**
 * Compact status-pill label for one passenger, in the reference app's style:
 * `RAC - 10`, `WL - 45`, `CNF B4-32`. Uses the current (chart) status, falling back
 * to the booking status. Confirmed seats append the coach/seat from the passenger
 * row (or from a `CNF/B4/32` raw status). Null when no status is known at all —
 * the caller renders no pill for that passenger.
 */
fun passengerPillLabel(passenger: TrainPassenger): String? {
    val status = passenger.currentStatus.trim().ifEmpty { passenger.bookingStatus.trim() }
    if (status.isEmpty()) return null
    if (status.startsWith(CONFIRMED_PREFIX, ignoreCase = true)) {
        val coach = passenger.coach.trim()
        val seat = passenger.seatBerth.trim()
        val fromRow = seatSuffix(coach, seat)
        if (fromRow != null) return "$CONFIRMED_PREFIX $fromRow"
        CNF_WITH_SEAT.matchEntire(status)?.let { match ->
            return "$CONFIRMED_PREFIX ${seatSuffix(match.groupValues[1], match.groupValues[2].trim())}"
        }
        return CONFIRMED_PREFIX
    }
    STATUS_WITH_NUMBER.matchEntire(status)?.let { match ->
        return "${match.groupValues[1].uppercase()} - ${match.groupValues[2]}"
    }
    return status
}

private fun seatSuffix(
    coach: String,
    seat: String,
): String? =
    when {
        coach.isNotEmpty() && seat.isNotEmpty() -> "$coach-$seat"
        coach.isNotEmpty() -> coach
        seat.isNotEmpty() -> seat
        else -> null
    }
