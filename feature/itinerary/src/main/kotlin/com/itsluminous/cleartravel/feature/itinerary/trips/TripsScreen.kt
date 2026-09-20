package com.itsluminous.cleartravel.feature.itinerary.trips

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.itsluminous.cleartravel.core.designsystem.component.ChipRow
import com.itsluminous.cleartravel.core.designsystem.component.ClearTravelFab
import com.itsluminous.cleartravel.core.designsystem.component.EmptyState
import com.itsluminous.cleartravel.core.designsystem.component.ExplainableIcon
import com.itsluminous.cleartravel.core.model.Trip
import com.itsluminous.cleartravel.feature.itinerary.R
import com.itsluminous.cleartravel.feature.itinerary.formatMedium
import com.itsluminous.cleartravel.feature.itinerary.labelRes
import com.itsluminous.cleartravel.feature.itinerary.parseCoverColor

/** Wraps the trip being edited (null = a brand-new trip) so one state drives the form. */
private data class TripFormTarget(
    val trip: Trip?,
)

/** Trips tab root: active/archived trip cards, add/edit form, delete confirmation. */
@Composable
internal fun TripsScreen(
    onOpenTrip: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TripsViewModel = hiltViewModel(),
) {
    val activeTrips by viewModel.activeTrips.collectAsStateWithLifecycle()
    val archivedTrips by viewModel.archivedTrips.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    var showArchived by rememberSaveable { mutableStateOf(false) }
    var formTarget by remember { mutableStateOf<TripFormTarget?>(null) }
    var deleteTarget by remember { mutableStateOf<Trip?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    LaunchedEffect(message) {
        val current = message ?: return@LaunchedEffect
        viewModel.consumeMessage()
        snackbarHostState.showSnackbar(context.getString(current.labelRes()))
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ClearTravelFab(onClick = { formTarget = TripFormTarget(trip = null) }) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.itinerary_add_trip),
                )
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            ChipRow(contentPadding = PaddingValues(horizontal = 16.dp)) {
                FilterChip(
                    selected = !showArchived,
                    onClick = { showArchived = false },
                    label = { Text(stringResource(R.string.itinerary_filter_active)) },
                )
                FilterChip(
                    selected = showArchived,
                    onClick = { showArchived = true },
                    label = { Text(stringResource(R.string.itinerary_filter_archived)) },
                )
            }
            val trips = if (showArchived) archivedTrips else activeTrips
            if (trips.isEmpty()) {
                if (showArchived) {
                    EmptyState(
                        icon = Icons.Filled.Archive,
                        title = stringResource(R.string.itinerary_empty_archived_title),
                        message = stringResource(R.string.itinerary_empty_archived_message),
                    )
                } else {
                    EmptyState(
                        icon = Icons.Filled.Map,
                        title = stringResource(R.string.itinerary_empty_title),
                        message = stringResource(R.string.itinerary_empty_message),
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(trips, key = { it.id }) { trip ->
                        TripCard(
                            trip = trip,
                            onClick = { onOpenTrip(trip.id) },
                            onEdit = { formTarget = TripFormTarget(trip = trip) },
                            onToggleArchive = { viewModel.setArchived(trip.id, !trip.archived) },
                            onDelete = { deleteTarget = trip },
                        )
                    }
                }
            }
        }
    }

    formTarget?.let { target ->
        TripFormDialog(
            trip = target.trip,
            onDismiss = { formTarget = null },
            onSave = { name, destination, startDate, endDate, emoji, color ->
                val accepted =
                    viewModel.saveTrip(target.trip, name, destination, startDate, endDate, emoji, color)
                if (accepted) formTarget = null
            },
        )
    }

    deleteTarget?.let { trip ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.itinerary_delete_trip_title)) },
            text = { Text(stringResource(R.string.itinerary_delete_trip_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteTrip(trip.id)
                        deleteTarget = null
                    },
                ) { Text(stringResource(R.string.itinerary_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.itinerary_cancel))
                }
            },
        )
    }
}

@Composable
private fun TripCard(
    trip: Trip,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onToggleArchive: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val coverColor = parseCoverColor(trip.coverColor) ?: MaterialTheme.colorScheme.primaryContainer
            Box(
                modifier = Modifier.size(48.dp).background(coverColor, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = trip.coverEmoji, style = MaterialTheme.typography.titleLarge)
            }
            Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(text = trip.name, style = MaterialTheme.typography.titleMedium)
                if (trip.destination.isNotBlank()) {
                    Text(
                        text = trip.destination,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                tripDateRangeText(trip)?.let { range ->
                    Text(
                        text = range,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            ExplainableIcon(
                icon = Icons.Filled.Edit,
                explanationRes = R.string.itinerary_edit_trip,
                targetSize = 40.dp,
                onClick = onEdit,
            )
            ExplainableIcon(
                icon = if (trip.archived) Icons.Filled.Unarchive else Icons.Filled.Archive,
                explanationRes =
                    if (trip.archived) R.string.itinerary_unarchive_trip else R.string.itinerary_archive_trip,
                targetSize = 40.dp,
                onClick = onToggleArchive,
            )
            ExplainableIcon(
                icon = Icons.Filled.Delete,
                explanationRes = R.string.itinerary_delete_trip,
                targetSize = 40.dp,
                onClick = onDelete,
            )
        }
    }
}

@Composable
private fun tripDateRangeText(trip: Trip): String? {
    val start = trip.startDate
    val end = trip.endDate
    return when {
        start != null && end != null ->
            stringResource(R.string.itinerary_trip_date_range, start.formatMedium(), end.formatMedium())
        start != null -> start.formatMedium()
        else -> null
    }
}
