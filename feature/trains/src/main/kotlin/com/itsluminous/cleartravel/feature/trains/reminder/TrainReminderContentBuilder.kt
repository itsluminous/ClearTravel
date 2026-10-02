package com.itsluminous.cleartravel.feature.trains.reminder

import com.itsluminous.cleartravel.core.data.sync.PnrHash
import com.itsluminous.cleartravel.core.model.TrainPassenger
import com.itsluminous.cleartravel.core.model.TrainTicket
import com.itsluminous.cleartravel.core.notifications.TrainReminderContent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * PURE builder of the reminder's wording inputs (ADR-044 §6). Owns the compact
 * "Sat 4 Oct" / "Sat 4 Oct, 16:35" patterns (feature-specific, like the card band —
 * `DateFormats` keeps the long localized forms) and the passenger status roll-up; the
 * strings themselves live in `core:notifications`.
 */
object TrainReminderContentBuilder {
    private const val DAY_PATTERN = "EEE d MMM"
    private const val DAY_TIME_PATTERN = "EEE d MMM, HH:mm"

    fun build(
        ticket: TrainTicket,
        passengers: List<TrainPassenger>,
        departure: TrainDeparture,
        now: Instant,
        zone: ZoneId,
        /** Label when the ticket has neither train number nor name, e.g. "PNR 8524167890" (a resource string). */
        fallbackLabel: (pnr: String) -> String,
        locale: Locale = Locale.getDefault(),
    ): TrainReminderContent {
        val at = departure.instant.atZone(zone)
        val day = DateTimeFormatter.ofPattern(DAY_PATTERN, locale)
        val dayTime = DateTimeFormatter.ofPattern(DAY_TIME_PATTERN, locale)
        return TrainReminderContent(
            pnr = ticket.pnr,
            trainLabel = trainLabel(ticket).ifEmpty { fallbackLabel(ticket.pnr) },
            boardingStation = ticket.fromStation.trim(),
            departureDayText = day.format(at),
            departureText = (if (departure.timeKnown) dayTime else day).format(at),
            daysUntilDeparture = ChronoUnit.DAYS.between(now.atZone(zone).toLocalDate(), at.toLocalDate()),
            statusSummary = statusSummary(passengers),
            notificationId = PnrHash.notificationId(ticket.pnr),
        )
    }

    /** "12951 Mumbai Rajdhani" — number and name, whichever are present. */
    fun trainLabel(ticket: TrainTicket): String =
        listOf(ticket.trainNumber, ticket.trainName)
            .map(String::trim)
            .filter(String::isNotEmpty)
            .joinToString(" ")

    /** Distinct effective statuses (current, falling back to booking) in passenger order; null when none is known. */
    fun statusSummary(passengers: List<TrainPassenger>): String? =
        passengers
            .sortedBy(TrainPassenger::sortOrder)
            .map { it.currentStatus.trim().ifEmpty { it.bookingStatus.trim() } }
            .filter(String::isNotEmpty)
            .distinct()
            .joinToString(", ")
            .takeIf(String::isNotEmpty)
}
