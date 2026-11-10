package com.itsluminous.cleartravel.feature.flights.list

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.itsluminous.cleartravel.core.designsystem.DateFormats
import com.itsluminous.cleartravel.core.designsystem.component.AutoShrinkText
import com.itsluminous.cleartravel.core.model.FlightJourney
import com.itsluminous.cleartravel.core.model.FlightStatus
import com.itsluminous.cleartravel.feature.flights.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Status name resource per [FlightStatus]. */
fun FlightStatus.labelRes(): Int =
    when (this) {
        FlightStatus.SCHEDULED -> R.string.flights_status_scheduled
        FlightStatus.BOARDING -> R.string.flights_status_boarding
        FlightStatus.DEPARTED -> R.string.flights_status_departed
        FlightStatus.LANDED -> R.string.flights_status_landed
        FlightStatus.DELAYED -> R.string.flights_status_delayed
        FlightStatus.CANCELLED -> R.string.flights_status_cancelled
        FlightStatus.UNKNOWN -> R.string.flights_status_unknown
    }

/** Small tonal chip whose container color encodes the flight status. */
@Composable
fun FlightStatusChip(
    status: FlightStatus,
    modifier: Modifier = Modifier,
) {
    val (container, content) = statusColors(status)
    AutoShrinkText(
        text = stringResource(status.labelRes()),
        style = MaterialTheme.typography.labelLarge,
        color = content,
        modifier =
            modifier
                .clip(RoundedCornerShape(8.dp))
                .background(container)
                .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
private fun statusColors(status: FlightStatus): Pair<Color, Color> =
    when (status) {
        FlightStatus.SCHEDULED ->
            MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        FlightStatus.BOARDING, FlightStatus.DEPARTED ->
            MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        FlightStatus.LANDED ->
            MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        FlightStatus.DELAYED, FlightStatus.CANCELLED ->
            MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        FlightStatus.UNKNOWN ->
            MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }

private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm")
private val DATE_FORMAT = DateTimeFormatter.ofPattern("EEE, d MMM uuuu", Locale.ENGLISH)
private val DAY_FORMAT = DateTimeFormatter.ofPattern("EEE, d MMM", Locale.ENGLISH)
private val TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.ENGLISH)

fun formatTime(
    instant: Instant?,
    zone: ZoneId = ZoneId.systemDefault(),
): String? = instant?.atZone(zone)?.toLocalTime()?.format(TIME_FORMAT)

fun formatDate(date: LocalDate?): String? = date?.format(DATE_FORMAT)

fun formatTimestamp(instant: Instant?): String? = instant?.atZone(ZoneId.systemDefault())?.format(TIMESTAMP_FORMAT)

/**
 * `Sun, 22 Nov` — the day of [instant] in [zone] when it is NOT [baseline] (the day the
 * surface already prints as its header); null when it is the same day or either side is
 * unknown. Lets the detail sheet append the arrival's date to `Scheduled: 07:24` only
 * for a leg that lands on another day (ADR-050 amendment).
 */
fun formatDayIfDiffers(
    instant: Instant?,
    baseline: LocalDate?,
    zone: ZoneId = ZoneId.systemDefault(),
): String? {
    val day = instant?.atZone(zone)?.toLocalDate() ?: return null
    if (baseline == null || day == baseline) return null
    return day.format(DAY_FORMAT)
}

/**
 * Calendar days the flight's displayed arrival lies after its displayed departure — the
 * `+1` an airline prints beside an overnight arrival. Both sides are the live time when
 * known (estimated, else scheduled), exactly what the card's `Dep · Arr` line shows; a
 * flight without any departure instant falls back to its header [FlightJourney.date].
 * Null when the arrival is unknown; 0 for a same-day landing.
 */
fun arrivalDayOffset(
    flight: FlightJourney,
    zone: ZoneId = ZoneId.systemDefault(),
): Int? {
    val arrival = flight.estArr ?: flight.schedArr ?: return null
    val departureDay = (flight.estDep ?: flight.schedDep)?.atZone(zone)?.toLocalDate() ?: flight.date
    return DateFormats.dayOffset(departureDay, arrival.atZone(zone).toLocalDate())
}

/** `07:24 +1` — [time] with the `+N` suffix when [dayOffset] is positive; [time] alone otherwise. */
fun withDayOffset(
    time: String,
    dayOffset: Int?,
    suffixTemplate: String,
): String = if (dayOffset != null && dayOffset > 0) "$time ${suffixTemplate.format(dayOffset)}" else time
