package com.itsluminous.cleartravel.feature.flights.list

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.itsluminous.cleartravel.core.model.FlightStatus
import com.itsluminous.cleartravel.feature.flights.R
import java.time.Instant
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
    Text(
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
private val TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.ENGLISH)

fun formatTime(instant: Instant?): String? = instant?.atZone(ZoneId.systemDefault())?.toLocalTime()?.format(TIME_FORMAT)

fun formatDate(date: java.time.LocalDate?): String? = date?.format(DATE_FORMAT)

fun formatTimestamp(instant: Instant?): String? = instant?.atZone(ZoneId.systemDefault())?.format(TIMESTAMP_FORMAT)
