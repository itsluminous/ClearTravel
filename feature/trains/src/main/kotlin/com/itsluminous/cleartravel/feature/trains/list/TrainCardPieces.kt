package com.itsluminous.cleartravel.feature.trains.list

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.itsluminous.cleartravel.core.model.TrainPassenger
import com.itsluminous.cleartravel.core.model.TrainTicket
import com.itsluminous.cleartravel.feature.trains.R
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** `Sep 29, Tue` — the header band's compact journey-date shape. */
private val BAND_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, EEE")

/**
 * Header band across the top of a train card (reference layout): origin code on the
 * left, destination code on the right, journey date + departure time centred.
 * Shared by the list card and the share image so both stay pixel-consistent.
 */
@Composable
internal fun TicketHeaderBand(
    ticket: TrainTicket,
    departureTime: String?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.primaryContainer)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val onBand = MaterialTheme.colorScheme.onPrimaryContainer
        Text(
            text = stationCode(ticket.fromStation),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = onBand,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = bandCenterText(ticket.journeyDate, departureTime),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = onBand,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(2f),
        )
        Text(
            text = stationCode(ticket.toStation),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = onBand,
            textAlign = TextAlign.End,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun bandCenterText(
    date: LocalDate?,
    departureTime: String?,
): String {
    val dateText = date?.let(BAND_DATE_FORMAT::format)
    return when {
        dateText != null && departureTime != null ->
            stringResource(R.string.trains_card_band_date_time, dateText, departureTime)
        dateText != null -> dateText
        departureTime != null -> departureTime
        else -> stringResource(R.string.trains_card_band_no_date)
    }
}

/**
 * Body lines of a train card: bold `number - name` title, `PNR …`, class/quota when
 * known, and the tertiary-toned "Updated X ago" freshness line.
 */
@Composable
internal fun TicketBodyLines(
    ticket: TrainTicket,
    now: Instant,
    modifier: Modifier = Modifier,
    trailingTitleContent: (@Composable () -> Unit)? = null,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = cardTitle(ticket).ifBlank { stringResource(R.string.trains_card_untitled) },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            trailingTitleContent?.invoke()
        }
        Text(
            text = stringResource(R.string.trains_card_pnr, ticket.pnr),
            style = MaterialTheme.typography.bodyLarge,
        )
        val classLine =
            listOf(ticket.travelClass, ticket.quota)
                .map(String::trim)
                .filter(String::isNotBlank)
        if (classLine.isNotEmpty()) {
            Text(
                text = classLine.joinToString(" · "),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val fetched = ticket.lastFetchedAt
        if (fetched != null) {
            Text(
                text = updatedAgoText(relativeAge(fetched, now)),
                style = MaterialTheme.typography.bodyLarge,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.tertiary,
            )
        } else {
            Text(
                text = stringResource(R.string.trains_card_never_fetched),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun updatedAgoText(age: RelativeAge): String =
    when (age) {
        RelativeAge.JustNow -> stringResource(R.string.trains_card_updated_just_now)
        is RelativeAge.Minutes ->
            pluralStringResource(R.plurals.trains_card_updated_minutes, age.value.toInt(), age.value)
        is RelativeAge.Hours ->
            pluralStringResource(R.plurals.trains_card_updated_hours, age.value.toInt(), age.value)
        is RelativeAge.Days ->
            pluralStringResource(R.plurals.trains_card_updated_days, age.value.toInt(), age.value)
    }

/** One compact filled pill per passenger with a known status (`RAC - 10`, `CNF B4-32`). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StatusPillRow(
    passengers: List<TrainPassenger>,
    modifier: Modifier = Modifier,
) {
    val labels = passengers.mapNotNull(::passengerPillLabel)
    if (labels.isEmpty()) return
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        labels.forEach { label ->
            Surface(
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(6.dp),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
        }
    }
}
