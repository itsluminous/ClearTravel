package com.itsluminous.cleartravel.feature.flights.list

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AirplaneTicket
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.itsluminous.cleartravel.core.designsystem.component.ExplainableIcon
import com.itsluminous.cleartravel.core.model.FlightJourney
import com.itsluminous.cleartravel.feature.flights.R

/**
 * The informational body of a flight card: `AI 101` + status chip, route, date,
 * dep/arr times, boarding-pass marker and — when [showFreshness] — the "Checked …"
 * line. Shared by the list card and the share image (ADR-039 part A) so the picture
 * a recipient gets looks exactly like the card; the share image turns freshness off
 * because "Checked 3 min ago" is meaningless to someone reading it later.
 */
@Composable
internal fun FlightCardBody(
    flight: FlightJourney,
    modifier: Modifier = Modifier,
    showFreshness: Boolean = true,
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${flight.airlineIata} ${flight.flightNumber}",
                style = MaterialTheme.typography.titleMedium,
            )
            FlightStatusChip(status = flight.status)
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                if (flight.depAirport.isNotBlank() || flight.arrAirport.isNotBlank()) {
                    Text(
                        text =
                            listOf(flight.depAirport, flight.arrAirport)
                                .filter { it.isNotBlank() }
                                .joinToString(" ${stringResource(R.string.flights_card_route_separator)} "),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                formatDate(flight.date)?.let {
                    Text(text = it, style = MaterialTheme.typography.bodyMedium)
                }
                val dep = formatTime(flight.estDep ?: flight.schedDep)
                val arr = formatTime(flight.estArr ?: flight.schedArr)
                if (dep != null || arr != null) {
                    Text(
                        text =
                            stringResource(
                                R.string.flights_card_dep_arr,
                                dep ?: stringResource(R.string.flights_value_unknown),
                                arr ?: stringResource(R.string.flights_value_unknown),
                            ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            if (flight.boardingPassPath != null && showFreshness) {
                ExplainableIcon(
                    icon = Icons.AutoMirrored.Filled.AirplaneTicket,
                    explanationRes = R.string.flights_icon_boarding_pass,
                    targetSize = 32.dp,
                    iconSize = 20.dp,
                )
            }
        }
        if (showFreshness) {
            Text(
                text =
                    formatTimestamp(flight.lastFetchedAt)
                        ?.let { stringResource(R.string.flights_last_fetched, it) }
                        ?: stringResource(R.string.flights_never_fetched),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
