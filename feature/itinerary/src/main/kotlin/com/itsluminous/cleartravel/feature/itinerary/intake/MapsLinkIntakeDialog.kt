package com.itsluminous.cleartravel.feature.itinerary.intake

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.itsluminous.cleartravel.core.model.Trip
import com.itsluminous.cleartravel.feature.itinerary.R

/**
 * App-shell hook (ADR-029 part D): drives the "Add place from Google Maps" intake
 * for a shared text carrying a Maps link. Starts when [sharedText] arrives, shows the
 * dialog while active, and reports [onDone] with the trip the place was added to (the
 * shell lands on it) or [onCancelled]. Rendered over the shell like the file intake.
 */
@Composable
fun MapsLinkIntakeHost(
    sharedText: String?,
    onDone: (tripId: String) -> Unit,
    onCancelled: () -> Unit,
    viewModel: MapsLinkIntakeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val trips by viewModel.trips.collectAsStateWithLifecycle()

    LaunchedEffect(sharedText) {
        if (sharedText != null) viewModel.start(sharedText) else viewModel.reset()
    }
    LaunchedEffect(state.addedToTripId) {
        state.addedToTripId?.let { tripId ->
            onDone(tripId)
            viewModel.reset()
        }
    }

    if (state.active && state.addedToTripId == null) {
        MapsLinkIntakeDialog(
            state = state,
            trips = trips,
            onTargetChange = viewModel::setTarget,
            onNewTripNameChange = viewModel::setNewTripName,
            onSelectTrip = viewModel::selectTrip,
            onConfirm = viewModel::confirm,
            onCancel = {
                viewModel.reset()
                onCancelled()
            },
        )
    }
}

@Composable
internal fun MapsLinkIntakeDialog(
    state: MapsIntakeState,
    trips: List<Trip>,
    onTargetChange: (MapsIntakeTarget) -> Unit,
    onNewTripNameChange: (String) -> Unit,
    onSelectTrip: (tripId: String) -> Unit,
    onConfirm: (fallbackName: String) -> Unit,
    onCancel: () -> Unit,
) {
    val fallbackName = stringResource(R.string.itinerary_maps_unknown_place)
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.itinerary_maps_intake_title)) },
        text = {
            Column {
                PlaceSummary(state = state, fallbackName = fallbackName)
                TargetOption(
                    selected = state.target == MapsIntakeTarget.NEW_TRIP,
                    label = stringResource(R.string.itinerary_maps_target_new_trip),
                    onClick = { onTargetChange(MapsIntakeTarget.NEW_TRIP) },
                )
                if (state.target == MapsIntakeTarget.NEW_TRIP) {
                    OutlinedTextField(
                        value = state.newTripName,
                        onValueChange = onNewTripNameChange,
                        label = { Text(stringResource(R.string.itinerary_trip_name_label)) },
                        placeholder = { Text(state.place?.name ?: fallbackName) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(start = 40.dp, bottom = 8.dp),
                    )
                }
                TargetOption(
                    selected = state.target == MapsIntakeTarget.EXISTING_TRIP,
                    label = stringResource(R.string.itinerary_maps_target_existing_trip),
                    enabled = trips.isNotEmpty(),
                    onClick = { onTargetChange(MapsIntakeTarget.EXISTING_TRIP) },
                )
                if (state.target == MapsIntakeTarget.EXISTING_TRIP) {
                    if (trips.isEmpty()) {
                        Text(
                            text = stringResource(R.string.itinerary_maps_no_trips),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 40.dp),
                        )
                    } else {
                        LazyColumn(modifier = Modifier.heightIn(max = 200.dp).padding(start = 24.dp)) {
                            items(trips, key = { it.id }) { trip ->
                                TargetOption(
                                    selected = state.selectedTripId == trip.id,
                                    label = listOf(trip.coverEmoji, trip.name).filter { it.isNotBlank() }.joinToString(" "),
                                    onClick = { onSelectTrip(trip.id) },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(fallbackName) }, enabled = state.canConfirm(trips)) {
                Text(stringResource(R.string.itinerary_maps_add_place))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.itinerary_cancel)) }
        },
    )
}

@Composable
private fun PlaceSummary(
    state: MapsIntakeState,
    fallbackName: String,
) {
    val place = state.place
    Text(
        text = place?.name ?: fallbackName,
        style = MaterialTheme.typography.titleMedium,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
    val detail =
        when {
            state.resolving -> stringResource(R.string.itinerary_maps_resolving)
            place != null && place.hasLocation ->
                stringResource(R.string.itinerary_maps_coordinates, place.latitude ?: 0.0, place.longitude ?: 0.0)
            else -> stringResource(R.string.itinerary_maps_no_coordinates)
        }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
        if (state.resolving) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp).padding(end = 8.dp), strokeWidth = 2.dp)
        }
        Text(
            text = detail,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TargetOption(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
                .padding(vertical = 4.dp),
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 8.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
