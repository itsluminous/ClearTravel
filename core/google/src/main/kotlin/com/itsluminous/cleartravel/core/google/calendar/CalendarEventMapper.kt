package com.itsluminous.cleartravel.core.google.calendar

import com.itsluminous.cleartravel.core.model.FlightJourney
import com.itsluminous.cleartravel.core.model.ItineraryItem
import com.itsluminous.cleartravel.core.model.ItineraryItemType
import com.itsluminous.cleartravel.core.model.TrainPassenger
import com.itsluminous.cleartravel.core.model.TrainRouteStop
import com.itsluminous.cleartravel.core.model.TrainTicket
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeParseException

/**
 * Localized building blocks for event text (hard rule 1: no hardcoded user-visible
 * strings — the calendar event IS user-visible). The caller resolves these from
 * `core:google`'s string resources via [java.lang.String.format]-style templates;
 * the mapper itself stays pure and JVM-testable.
 */
data class CalendarEventStrings(
    /** `%1$s` = train number+name, `%2$s` = from, `%3$s` = to. */
    val trainSummary: String,
    /** `%1$s` = flight designator (e.g. "6E 2345"), `%2$s` = from, `%3$s` = to. */
    val flightSummary: String,
    /** `%1$s` = from, `%2$s` = to. */
    val commuteSummary: String,
    /** `%1$s` = PNR / booking reference. */
    val pnrLine: String,
    /** `%1$s` = seat. */
    val seatLine: String,
    /** `%1$s` = passenger name, `%2$s` = coach+seat, `%3$s` = status. */
    val passengerLine: String,
)

/** Private extended-property keys stamped on every pushed event. */
object CalendarEventProps {
    const val ROW_ID = "cleartravelId"
    const val MANAGED = "cleartravelManaged"
    const val MANAGED_VALUE = "1"
}

/**
 * Pure row → [CalendarEvent] mapping (spec feature 5): one event per itinerary item
 * and per train/flight journey. Rows without any usable date return null — they are
 * unsyncable, never an error. No I/O; unit-tested against fixtures of each shape.
 */
object CalendarEventMapper {
    /** Default duration of a timed place visit / commute leg. */
    private const val DEFAULT_EVENT_HOURS = 1L

    /**
     * PLACE: summary = name, location = name + coordinates, timed at [ItineraryItem.plannedTime]
     * when set (1h), all-day otherwise. COMMUTE: summary = "from → to".
     */
    fun itineraryEvent(
        item: ItineraryItem,
        strings: CalendarEventStrings,
        zone: ZoneId,
    ): CalendarEvent? {
        val date = item.date ?: return null
        val summary =
            when (item.type) {
                ItineraryItemType.PLACE -> item.name
                ItineraryItemType.COMMUTE -> strings.commuteSummary.format(item.fromName, item.toName)
            }
        val location =
            when (item.type) {
                ItineraryItemType.PLACE -> placeLocation(item)
                ItineraryItemType.COMMUTE -> item.fromName
            }
        val time = parseTime(item.plannedTime)
        val base =
            CalendarEvent(
                summary = summary,
                description = item.note,
                location = location,
                privateProperties = props(item.id),
            )
        return if (time != null) {
            val start = LocalDateTime.of(date, time)
            base.copy(startDateTime = start, endDateTime = start.plusHours(DEFAULT_EVENT_HOURS), timeZone = zone.id)
        } else {
            base.copy(startDate = date, endDateExclusive = date.plusDays(1))
        }
    }

    /**
     * Train journey: departure→arrival timed event when the route's first/last stop
     * times are known (arrival day offset honored), all-day on the journey date
     * otherwise. Location = origin station; description = PNR + per-passenger
     * seat/status summary.
     */
    fun trainEvent(
        ticket: TrainTicket,
        passengers: List<TrainPassenger>,
        routeStops: List<TrainRouteStop>,
        strings: CalendarEventStrings,
        zone: ZoneId,
    ): CalendarEvent? {
        val date = ticket.journeyDate ?: return null
        val trainLabel = listOf(ticket.trainNumber, ticket.trainName).filter(String::isNotBlank).joinToString(" ")
        val summary = strings.trainSummary.format(trainLabel, ticket.fromStation, ticket.toStation)
        val description =
            buildList {
                if (ticket.pnr.isNotBlank()) add(strings.pnrLine.format(ticket.pnr))
                passengers.forEach { p ->
                    val seat = listOf(p.coach, p.seatBerth).filter(String::isNotBlank).joinToString(" ")
                    val status = p.currentStatus.ifBlank { p.bookingStatus }
                    add(strings.passengerLine.format(p.name, seat, status))
                }
            }.joinToString("\n")
        val base =
            CalendarEvent(
                summary = summary,
                description = description,
                location = ticket.fromStation,
                privateProperties = props(ticket.id),
            )
        val firstStop = routeStops.minByOrNull { it.sortOrder }
        val lastStop = routeStops.maxByOrNull { it.sortOrder }
        val depTime = firstStop?.let { parseTime(it.departure) }
        val arrTime = lastStop?.let { parseTime(it.arrival) }
        return if (depTime != null && arrTime != null && firstStop != null && lastStop != null) {
            val arrivalDate = date.plusDays((lastStop.day - firstStop.day).coerceAtLeast(0).toLong())
            base.copy(
                startDateTime = LocalDateTime.of(date, depTime),
                endDateTime = LocalDateTime.of(arrivalDate, arrTime),
                timeZone = zone.id,
            )
        } else {
            base.copy(startDate = date, endDateExclusive = date.plusDays(1))
        }
    }

    /**
     * Flight journey: estimated-over-scheduled departure→arrival timed event when
     * known, all-day on the flight date otherwise. Location = departure airport;
     * description = PNR + seat summary.
     */
    fun flightEvent(
        flight: FlightJourney,
        strings: CalendarEventStrings,
        zone: ZoneId,
    ): CalendarEvent? {
        val designator = "${flight.airlineIata} ${flight.flightNumber}".trim()
        val summary = strings.flightSummary.format(designator, flight.depAirport, flight.arrAirport)
        val description =
            buildList {
                if (flight.pnrBookingRef.isNotBlank()) add(strings.pnrLine.format(flight.pnrBookingRef))
                if (flight.seat.isNotBlank()) add(strings.seatLine.format(flight.seat))
            }.joinToString("\n")
        val base =
            CalendarEvent(
                summary = summary,
                description = description,
                location = flight.depAirport,
                privateProperties = props(flight.id),
            )
        val dep = flight.estDep ?: flight.schedDep
        val arr = flight.estArr ?: flight.schedArr
        return when {
            dep != null -> {
                val start = LocalDateTime.ofInstant(dep, zone)
                val end = (arr ?: dep.plusSeconds(DEFAULT_FLIGHT_SECONDS)).let { LocalDateTime.ofInstant(it, zone) }
                base.copy(startDateTime = start, endDateTime = end, timeZone = zone.id)
            }
            flight.date != null -> base.copy(startDate = flight.date, endDateExclusive = flight.date!!.plusDays(1))
            else -> null
        }
    }

    private fun placeLocation(item: ItineraryItem): String {
        val lat = item.latitude
        val lng = item.longitude
        return if (lat != null && lng != null) "${item.name} ($lat, $lng)" else item.name
    }

    private fun parseTime(value: String): LocalTime? =
        if (value.isBlank()) {
            null
        } else {
            try {
                LocalTime.parse(value)
            } catch (e: DateTimeParseException) {
                null
            }
        }

    private fun props(rowId: String): Map<String, String> =
        mapOf(
            CalendarEventProps.ROW_ID to rowId,
            CalendarEventProps.MANAGED to CalendarEventProps.MANAGED_VALUE,
        )

    /** Fallback flight duration when only departure is known (2h). */
    private const val DEFAULT_FLIGHT_SECONDS = 2L * 60 * 60
}
