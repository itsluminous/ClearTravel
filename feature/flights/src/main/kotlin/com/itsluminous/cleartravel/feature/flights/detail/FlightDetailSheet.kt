package com.itsluminous.cleartravel.feature.flights.detail

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.itsluminous.cleartravel.core.designsystem.component.AutoShrinkText
import com.itsluminous.cleartravel.core.model.FlightJourney
import com.itsluminous.cleartravel.feature.flights.R
import com.itsluminous.cleartravel.feature.flights.list.FlightStatusChip
import com.itsluminous.cleartravel.feature.flights.list.formatDate
import com.itsluminous.cleartravel.feature.flights.list.formatTime
import com.itsluminous.cleartravel.feature.flights.list.formatTimestamp
import com.itsluminous.cleartravel.feature.flights.status.CheckOutcome
import com.itsluminous.cleartravel.feature.flights.status.CheckOutcomeKind
import com.itsluminous.cleartravel.feature.flights.status.FlightStatusFallbacks

/**
 * Everything about one flight + the spec's MANDATORY manual trigger buttons:
 * "Check status" (scrape flow), "Open web check-in" (per-airline URL from the
 * check-in data file, web-search fallback), "View boarding pass" — plus the
 * documents row (boarding pass + attached booking confirmations, each opening the
 * full-brightness viewer) and "Attach booking confirmation" (ADR-017).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlightDetailSheet(
    flight: FlightJourney,
    onDismiss: () -> Unit,
    onCheckStatus: () -> Unit,
    onEdit: () -> Unit,
    onViewPass: (path: String) -> Unit,
    onArchiveToggle: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    /** Transient last-attempt outcome (D2) — rendered under the fetched timestamp. */
    lastCheckOutcome: CheckOutcome? = null,
    /** Documents list (boarding pass + attachments); see [buildFlightDocuments]. */
    documents: List<FlightDocument> = emptyList(),
    /** Opens a documents-row entry in the full-brightness viewer. */
    onOpenDocument: (FlightDocument) -> Unit = {},
    /** Launches the picker to attach a booking confirmation to THIS flight. */
    onAttachBooking: () -> Unit = {},
) {
    val context = LocalContext.current
    var confirmDelete by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, modifier = modifier) {
        Column(
            modifier =
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${flight.airlineIata} ${flight.flightNumber}",
                    style = MaterialTheme.typography.headlineSmall,
                )
                FlightStatusChip(status = flight.status)
            }
            formatDate(flight.date)?.let { Text(text = it, style = MaterialTheme.typography.bodyLarge) }
            if (flight.depAirport.isNotBlank() || flight.arrAirport.isNotBlank()) {
                Text(
                    text =
                        listOf(flight.depAirport, flight.arrAirport)
                            .filter { it.isNotBlank() }
                            .joinToString(" ${stringResource(R.string.flights_card_route_separator)} "),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            EndpointBlock(
                titleRes = R.string.flights_label_departure,
                scheduled = formatTime(flight.schedDep),
                estimated = formatTime(flight.estDep),
                terminal = flight.depTerminal,
                gate = flight.depGate,
            )
            EndpointBlock(
                titleRes = R.string.flights_label_arrival,
                scheduled = formatTime(flight.schedArr),
                estimated = formatTime(flight.estArr),
                terminal = flight.arrTerminal,
                gate = flight.arrGate,
            )

            DetailRow(labelRes = R.string.flights_label_belt, value = flight.baggageBelt)
            DetailRow(labelRes = R.string.flights_label_aircraft, value = flight.aircraftType)
            DetailRow(labelRes = R.string.flights_label_seat, value = flight.seat)
            DetailRow(labelRes = R.string.flights_label_cabin, value = flight.cabinClass)
            DetailRow(labelRes = R.string.flights_label_pnr, value = flight.pnrBookingRef)

            Text(
                text =
                    formatTimestamp(flight.lastFetchedAt)
                        ?.let { stringResource(R.string.flights_last_fetched, it) }
                        ?: stringResource(R.string.flights_never_fetched),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (lastCheckOutcome != null) {
                val at = formatTimestamp(lastCheckOutcome.at).orEmpty()
                Text(
                    text =
                        stringResource(
                            when (lastCheckOutcome.kind) {
                                CheckOutcomeKind.UPDATED -> R.string.flights_outcome_updated
                                CheckOutcomeKind.NO_CHANGES -> R.string.flights_outcome_no_changes
                                CheckOutcomeKind.FAILED -> R.string.flights_outcome_failed
                            },
                            at,
                        ),
                    style = MaterialTheme.typography.bodySmall,
                    color =
                        if (lastCheckOutcome.kind == CheckOutcomeKind.FAILED) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            FilledTonalButton(onClick = onCheckStatus, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.flights_action_check_status))
            }
            FilledTonalButton(
                onClick = {
                    val url =
                        flight.checkInUrl
                            ?: FlightStatusFallbacks.checkInSearchUrl(flight.airlineIata)
                    context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.flights_action_web_checkin))
            }
            flight.boardingPassPath?.let { path ->
                FilledTonalButton(onClick = { onViewPass(path) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.flights_action_view_pass))
                }
            }
            FilledTonalButton(onClick = onAttachBooking, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.flights_action_attach_booking))
            }

            if (documents.isNotEmpty()) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Text(
                    text = stringResource(R.string.flights_documents_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                documents.forEach { document ->
                    ListItem(
                        headlineContent = {
                            Text(
                                stringResource(
                                    when (document.type) {
                                        FlightDocumentType.BOARDING_PASS -> R.string.flights_doc_boarding_pass
                                        FlightDocumentType.BOOKING_CONFIRMATION ->
                                            R.string.flights_doc_booking_confirmation
                                    },
                                ),
                            )
                        },
                        supportingContent = { Text(stringResource(R.string.flights_doc_open_hint)) },
                        leadingContent = { Icon(Icons.Filled.Description, contentDescription = null) },
                        modifier = Modifier.clickable { onOpenDocument(document) },
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Three equal-width buttons: the longer "Unarchive" must shrink to
                // stay on one line rather than wrap; siblings get the same treatment.
                OutlinedButton(onClick = onEdit, modifier = Modifier.weight(1f)) {
                    AutoShrinkText(stringResource(R.string.flights_action_edit))
                }
                OutlinedButton(onClick = onArchiveToggle, modifier = Modifier.weight(1f)) {
                    AutoShrinkText(
                        stringResource(
                            if (flight.archived) R.string.flights_action_unarchive else R.string.flights_action_archive,
                        ),
                    )
                }
                OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.weight(1f)) {
                    AutoShrinkText(stringResource(R.string.flights_action_delete))
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.flights_delete_title)) },
            text = { Text(stringResource(R.string.flights_delete_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        onDelete()
                    },
                ) { Text(stringResource(R.string.flights_delete_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.flights_delete_cancel))
                }
            },
        )
    }
}

@Composable
private fun EndpointBlock(
    titleRes: Int,
    scheduled: String?,
    estimated: String?,
    terminal: String,
    gate: String,
) {
    val unknown = stringResource(R.string.flights_value_unknown)
    Column {
        Text(text = stringResource(titleRes), style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                text = "${stringResource(R.string.flights_label_scheduled)}: ${scheduled ?: unknown}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "${stringResource(R.string.flights_label_estimated)}: ${estimated ?: unknown}",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                text = "${stringResource(R.string.flights_label_terminal)}: ${terminal.ifBlank { unknown }}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "${stringResource(R.string.flights_label_gate)}: ${gate.ifBlank { unknown }}",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun DetailRow(
    labelRes: Int,
    value: String,
) {
    if (value.isBlank()) return
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}
