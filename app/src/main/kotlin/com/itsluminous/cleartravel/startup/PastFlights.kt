package com.itsluminous.cleartravel.startup

import com.itsluminous.cleartravel.core.model.FlightJourney
import java.time.LocalDate
import java.time.ZoneId

/**
 * True when [flight]'s journey day is strictly before [today] — a candidate for
 * auto-archiving (the journey day itself is NOT past: the user may still be
 * travelling; same convention as `feature:trains`' `isPastJourney`). Falls back to
 * the scheduled departure's local date when the flight has no [FlightJourney.date];
 * flights with neither are never considered past.
 *
 * Lives in the app module because it composes the trains + flights aggregates the
 * shell archives together — feature modules never see each other (ADR-001).
 */
fun isPastFlight(
    flight: FlightJourney,
    today: LocalDate,
    zone: ZoneId = ZoneId.systemDefault(),
): Boolean {
    val date = flight.date ?: flight.schedDep?.atZone(zone)?.toLocalDate() ?: return false
    return date.isBefore(today)
}
