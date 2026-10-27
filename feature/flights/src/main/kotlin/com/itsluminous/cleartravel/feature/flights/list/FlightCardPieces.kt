package com.itsluminous.cleartravel.feature.flights.list

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AirplaneTicket
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.itsluminous.cleartravel.core.designsystem.component.AutoShrinkText
import com.itsluminous.cleartravel.core.designsystem.component.ExplainableIcon
import com.itsluminous.cleartravel.core.model.FlightPassenger
import com.itsluminous.cleartravel.core.model.FlightWithPassengers
import com.itsluminous.cleartravel.feature.flights.R

/**
 * The informational body of a flight card: `AI 101` + status chip, route, date,
 * dep/arr times, boarding-pass marker and — when [showFreshness] — the "Checked …"
 * line. Shared by the list card and the share image (ADR-039 part A) so the picture
 * a recipient gets looks exactly like the card; the share image turns freshness off
 * because "Checked 3 min ago" is meaningless to someone reading it later.
 *
 * The boarding-pass marker is a real control: a tap opens the pass in the shared
 * document viewer through [onViewPass] (user report 2026-09-23: the marker used to
 * swallow the tap and do nothing) — with several passengers' passes (ADR-046) it opens a
 * small chooser first. Long-press still explains it. Seats are joined (`Seats 12A, 12B`);
 * the share image (freshness off) lists the passengers by name as well.
 */
@Composable
internal fun FlightCardBody(
    entry: FlightWithPassengers,
    modifier: Modifier = Modifier,
    showFreshness: Boolean = true,
    onViewPass: ((path: String) -> Unit)? = null,
) {
    val flight = entry.flight
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Chip first (measured before the weighted title), title shrinks to what is left.
            AutoShrinkText(
                text = "${flight.airlineIata} ${flight.flightNumber}",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            FlightStatusChip(status = flight.status)
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                if (flight.depAirport.isNotBlank() || flight.arrAirport.isNotBlank()) {
                    AutoShrinkText(
                        text =
                            listOf(flight.depAirport, flight.arrAirport)
                                .filter { it.isNotBlank() }
                                .joinToString(" ${stringResource(R.string.flights_card_route_separator)} "),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                formatDate(flight.date)?.let {
                    AutoShrinkText(text = it, style = MaterialTheme.typography.bodyMedium)
                }
                val dep = formatTime(flight.estDep ?: flight.schedDep)
                val arr = formatTime(flight.estArr ?: flight.schedArr)
                if (dep != null || arr != null) {
                    AutoShrinkText(
                        text =
                            stringResource(
                                R.string.flights_card_dep_arr,
                                dep ?: stringResource(R.string.flights_value_unknown),
                                arr ?: stringResource(R.string.flights_value_unknown),
                            ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                val seats = entry.seats
                if (seats.isNotEmpty()) {
                    AutoShrinkText(
                        text =
                            stringResource(
                                if (seats.size == 1) R.string.flights_card_seat else R.string.flights_card_seats,
                                seats.joinToString(stringResource(R.string.flights_card_seat_separator)),
                            ),
                        style = MaterialTheme.typography.bodyMedium,
                        minScale = 0.8f,
                    )
                }
                if (!showFreshness) {
                    // Share image: who is on board, so the picture stands on its own.
                    val names = entry.passengers.map { it.name.trim() }.filter { it.isNotBlank() }
                    if (names.isNotEmpty()) {
                        AutoShrinkText(
                            text = names.joinToString(stringResource(R.string.flights_card_passenger_separator)),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            minScale = 0.8f,
                        )
                    }
                }
            }
            val passes = entry.boardingPasses
            if (passes.isNotEmpty() && showFreshness) {
                var choosing by remember { mutableStateOf(false) }
                Box {
                    ExplainableIcon(
                        icon = Icons.AutoMirrored.Filled.AirplaneTicket,
                        explanationRes = R.string.flights_icon_boarding_pass,
                        tint = MaterialTheme.colorScheme.primary,
                        targetSize = 40.dp,
                        iconSize = 22.dp,
                        onClick =
                            onViewPass?.let { open ->
                                { if (passes.size == 1) open(passes.single().path) else choosing = true }
                            },
                    )
                    // ADR-046: several passengers have a pass — ask whose.
                    DropdownMenu(expanded = choosing, onDismissRequest = { choosing = false }) {
                        Text(
                            text = stringResource(R.string.flights_pass_chooser_title),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                        passes.forEachIndexed { index, pass ->
                            DropdownMenuItem(
                                text = { AutoShrinkText(passengerLabel(pass.passenger, index), minScale = 0.8f) },
                                onClick = {
                                    choosing = false
                                    onViewPass?.invoke(pass.path)
                                },
                            )
                        }
                    }
                }
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

/** `Prakash Kumar`, else `Seat 12A`, else `Passenger N` (1-based) — the label every passenger row/menu uses (ADR-046). */
@Composable
internal fun passengerLabel(
    passenger: FlightPassenger?,
    index: Int,
): String =
    passenger?.name?.trim()?.takeIf { it.isNotBlank() }
        ?: passenger
            ?.seat
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { stringResource(R.string.flights_passenger_seat, it) }
        ?: stringResource(R.string.flights_passenger_unnamed, index + 1)
