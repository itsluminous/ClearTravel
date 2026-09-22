package com.itsluminous.cleartravel.feature.itinerary.logic

import com.itsluminous.cleartravel.core.model.TrainRouteStop
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// Planned-time derivation for a commute leg linked to a journey (ADR-029 part C).
// Pure: the ViewModel feeds it repository data and writes the result into the form.
// `feature:itinerary` must not depend on `feature:trains`, so the boarding-station
// match mirrors the trains card logic here rather than importing it.

/**
 * Scheduled departure "HH:mm" at the ticket's BOARDING station, matched by full
 * name, the " (CODE)" suffix the route mapper appends, or a name prefix; falls back
 * to the first stop of the route. Null when the route is unknown or the matched stop
 * has no departure (e.g. the route is not fetched yet).
 */
fun trainDepartureTime(
    stops: List<TrainRouteStop>,
    boardingStation: String,
): String? {
    val ordered = stops.sortedBy(TrainRouteStop::sortOrder)
    val query = boardingStation.trim()
    val boarding =
        if (query.isEmpty()) {
            null
        } else {
            ordered.firstOrNull { stop ->
                val name = stop.stationName.trim()
                name.equals(query, ignoreCase = true) ||
                    name.contains("($query)", ignoreCase = true) ||
                    name.startsWith(query, ignoreCase = true)
            }
        }
    return (boarding ?: ordered.firstOrNull())
        ?.departure
        ?.trim()
        ?.takeIf(String::isNotBlank)
}

/** A flight's scheduled departure rendered as the leg's "HH:mm" planned time in [zone]. */
fun flightDepartureTime(
    schedDep: Instant?,
    zone: ZoneId,
): String? = schedDep?.atZone(zone)?.toLocalTime()?.format(PLANNED_TIME_FORMAT)

private val PLANNED_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
