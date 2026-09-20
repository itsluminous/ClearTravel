package com.itsluminous.cleartravel.feature.trains.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.itsluminous.cleartravel.core.model.TrainPassenger
import com.itsluminous.cleartravel.core.model.TrainRouteStop
import com.itsluminous.cleartravel.feature.trains.R
import com.itsluminous.cleartravel.feature.trains.hasUnconfirmedSeat
import com.itsluminous.cleartravel.feature.trains.list.formatDate
import com.itsluminous.cleartravel.feature.trains.list.lastFetchedText

private const val MINUTES_PER_HOUR = 60L

/**
 * The ticket detail bottom sheet: PNR, per-passenger booking/current status and seat
 * details, train details, route stops (when present), derived journey duration,
 * last-fetched timestamp, and the four actions. Delete asks for confirmation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TrainDetailSheet(
    state: TrainDetailUiState,
    onDismiss: () -> Unit,
    onCheckStatus: () -> Unit,
    onFetchRoute: () -> Unit,
    onEdit: () -> Unit,
    onArchiveToggle: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ticket = state.ticket ?: return
    var showDeleteConfirm by rememberSaveable { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, modifier = modifier) {
        Column(
            modifier =
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.trains_detail_title),
                style = MaterialTheme.typography.titleLarge,
            )

            DetailRow(labelRes = R.string.trains_detail_pnr, value = ticket.pnr)
            DetailRow(
                labelRes = R.string.trains_detail_train,
                value = listOf(ticket.trainNumber, ticket.trainName).filter(String::isNotBlank).joinToString(" · "),
            )
            ticket.journeyDate?.let { date ->
                DetailRow(labelRes = R.string.trains_detail_date, value = formatDate(date))
            }
            if (ticket.fromStation.isNotBlank() || ticket.toStation.isNotBlank()) {
                DetailRow(
                    labelRes = R.string.trains_detail_route,
                    value = stringResource(R.string.trains_card_route, ticket.fromStation, ticket.toStation),
                )
            }
            DetailRow(labelRes = R.string.trains_detail_class, value = ticket.travelClass)
            DetailRow(labelRes = R.string.trains_detail_quota, value = ticket.quota)
            state.duration?.let { duration ->
                DetailRow(
                    labelRes = R.string.trains_detail_duration,
                    value =
                        stringResource(
                            R.string.trains_detail_duration_value,
                            duration.toHours(),
                            duration.toMinutes() % MINUTES_PER_HOUR,
                        ),
                )
            }
            Text(
                text = lastFetchedText(ticket),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (state.passengers.isNotEmpty()) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Text(
                    text = stringResource(R.string.trains_detail_passengers),
                    style = MaterialTheme.typography.titleMedium,
                )
                state.passengers.forEach { passenger ->
                    PassengerDetail(passenger = passenger)
                }
            }

            if (state.routeStops.isNotEmpty()) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Text(
                    text = stringResource(R.string.trains_detail_route_stops),
                    style = MaterialTheme.typography.titleMedium,
                )
                state.routeStops.forEach { stop ->
                    RouteStopDetail(stop = stop)
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            if (hasUnconfirmedSeat(state.passengers)) {
                Text(
                    text = stringResource(R.string.trains_detail_unconfirmed_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onClick = onCheckStatus, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.trains_detail_check_status))
            }
            if (ticket.trainNumber.isNotBlank()) {
                OutlinedButton(onClick = onFetchRoute, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.trains_detail_fetch_route))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onEdit, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.trains_detail_edit))
                }
                OutlinedButton(onClick = onArchiveToggle, modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(
                            if (ticket.archived) R.string.trains_detail_unarchive else R.string.trains_detail_archive,
                        ),
                    )
                }
                OutlinedButton(onClick = { showDeleteConfirm = true }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.trains_detail_delete))
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.trains_detail_delete_confirm_title)) },
            text = { Text(stringResource(R.string.trains_detail_delete_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        onDelete()
                    },
                ) {
                    Text(stringResource(R.string.trains_detail_delete_confirm_yes))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.trains_detail_delete_confirm_no))
                }
            },
        )
    }
}

@Composable
private fun DetailRow(
    labelRes: Int,
    value: String,
    modifier: Modifier = Modifier,
) {
    if (value.isBlank()) return
    Row(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(2f),
        )
    }
}

@Composable
private fun PassengerDetail(
    passenger: TrainPassenger,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        if (passenger.name.isNotBlank()) {
            Text(text = passenger.name, style = MaterialTheme.typography.bodyLarge)
        }
        if (passenger.coach.isNotBlank() || passenger.seatBerth.isNotBlank()) {
            Text(
                text = stringResource(R.string.trains_detail_seat, passenger.coach, passenger.seatBerth),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (passenger.bookingStatus.isNotBlank()) {
            Text(
                text = stringResource(R.string.trains_detail_booking_status, passenger.bookingStatus),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (passenger.currentStatus.isNotBlank()) {
            Text(
                text = stringResource(R.string.trains_detail_current_status, passenger.currentStatus),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun RouteStopDetail(
    stop: TrainRouteStop,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(
            text = stop.stationName,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Column {
            if (stop.arrival.isNotBlank() || stop.departure.isNotBlank()) {
                Text(
                    text = stringResource(R.string.trains_detail_stop_times, stop.arrival, stop.departure),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (stop.day > 1) {
                Text(
                    text = stringResource(R.string.trains_detail_stop_day, stop.day),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (stop.platform.isNotBlank()) {
                Text(
                    text = stringResource(R.string.trains_detail_stop_platform, stop.platform),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
