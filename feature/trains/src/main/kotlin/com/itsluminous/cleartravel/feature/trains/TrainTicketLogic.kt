package com.itsluminous.cleartravel.feature.trains

import com.itsluminous.cleartravel.core.model.TrainRouteStop
import com.itsluminous.cleartravel.core.model.TrainTicket
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** Length of a valid Indian Railways PNR. */
private const val PNR_LENGTH = 10

private const val MINUTES_PER_DAY = 24L * 60L

private val STOP_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("H:mm")

/** True when [pnr] is a well-formed Indian Railways PNR: exactly 10 digits. */
fun isValidPnr(pnr: String): Boolean {
    val trimmed = pnr.trim()
    return trimmed.length == PNR_LENGTH && trimmed.all(Char::isDigit)
}

/**
 * True when [ticket]'s journey date is strictly before [today] — the ticket is a
 * candidate for auto-archiving (the journey day itself is NOT past: the user may
 * still be travelling). Tickets without a journey date are never considered past.
 */
fun isPastJourney(
    ticket: TrainTicket,
    today: LocalDate,
): Boolean {
    val date = ticket.journeyDate ?: return false
    return date.isBefore(today)
}

/**
 * Journey duration derived from the route: first stop's departure to last stop's
 * arrival, honoring the running-day offsets. Null when there are fewer than two
 * stops or either time is missing/unparseable.
 */
fun journeyDuration(stops: List<TrainRouteStop>): Duration? {
    if (stops.size < 2) return null
    val ordered = stops.sortedBy(TrainRouteStop::sortOrder)
    val first = ordered.first()
    val last = ordered.last()
    val departure = parseStopTime(first.departure) ?: return null
    val arrival = parseStopTime(last.arrival) ?: return null
    val dayOffsetMinutes = (last.day - first.day).toLong() * MINUTES_PER_DAY
    val minutes =
        dayOffsetMinutes +
            Duration.between(departure, arrival).toMinutes()
    return if (minutes > 0) Duration.ofMinutes(minutes) else null
}

private fun parseStopTime(value: String): LocalTime? = runCatching { LocalTime.parse(value.trim(), STOP_TIME_FORMAT) }.getOrNull()
