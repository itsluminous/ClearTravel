package com.itsluminous.cleartravel.feature.trains.reminder

import com.itsluminous.cleartravel.core.model.TrainRouteStop
import com.itsluminous.cleartravel.core.model.TrainTicket
import com.itsluminous.cleartravel.feature.trains.list.departureTime
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * When a ticket's journey starts, as far as the stored data can say (ADR-044 §2).
 * [instant] is the boarding-station departure (`journeyDate` + the route's scheduled
 * departure for `fromStation`, the same match the card and the itinerary leg use) in
 * [zone]; when the route is not stored yet it is the START of the journey day — the
 * earliest the train could leave, so a reminder derived from it is never late.
 * [timeKnown] tells the notification whether to print a clock time.
 */
data class TrainDeparture(
    val instant: Instant,
    val timeKnown: Boolean,
) {
    companion object {
        private val STOP_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("H:mm")

        /** Null when the ticket has no journey date — such a ticket can never be reminded about. */
        fun of(
            ticket: TrainTicket,
            stops: List<TrainRouteStop>,
            zone: ZoneId,
        ): TrainDeparture? {
            val date = ticket.journeyDate ?: return null
            val time = departureTime(stops, ticket.fromStation)?.let(::parseStopTime)
            val local: ZonedDateTime = if (time != null) date.atTime(time).atZone(zone) else date.atStartOfDay(zone)
            return TrainDeparture(instant = local.toInstant(), timeKnown = time != null)
        }

        private fun parseStopTime(value: String): LocalTime? = runCatching { LocalTime.parse(value.trim(), STOP_TIME_FORMAT) }.getOrNull()
    }
}
