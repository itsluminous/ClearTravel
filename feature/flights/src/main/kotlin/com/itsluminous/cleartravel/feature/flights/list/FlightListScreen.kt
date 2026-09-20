package com.itsluminous.cleartravel.feature.flights.list

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AirplaneTicket
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.itsluminous.cleartravel.core.designsystem.component.ChipRow
import com.itsluminous.cleartravel.core.designsystem.component.ClearTravelCard
import com.itsluminous.cleartravel.core.designsystem.component.ClearTravelFab
import com.itsluminous.cleartravel.core.designsystem.component.EmptyState
import com.itsluminous.cleartravel.core.designsystem.component.ExplainableIcon
import com.itsluminous.cleartravel.core.model.FlightJourney
import com.itsluminous.cleartravel.feature.flights.R
import com.itsluminous.cleartravel.feature.flights.detail.FlightDetailSheet
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlightListScreen(
    onAddManual: () -> Unit,
    onImportPass: (uriString: String) -> Unit,
    onEdit: (flightId: String) -> Unit,
    onCheckStatus: (flightId: String) -> Unit,
    onViewPass: (path: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FlightListViewModel = hiltViewModel(),
    /** Deep-link hook: opens this flight's detail sheet on first composition. */
    initialDetailFlightId: String? = null,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showAddOptions by remember { mutableStateOf(false) }
    var detailFlightId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(initialDetailFlightId) {
        if (initialDetailFlightId != null) detailFlightId = initialDetailFlightId
    }

    val passPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { onImportPass(it.toString()) }
        }

    val archivedSnackbar = stringResource(R.string.flights_archived_snackbar)
    val unarchivedSnackbar = stringResource(R.string.flights_unarchived_snackbar)
    val deletedSnackbar = stringResource(R.string.flights_deleted_snackbar)

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ClearTravelFab(onClick = { showAddOptions = true }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.flights_add))
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            ChipRow(
                contentPadding =
                    androidx.compose.foundation.layout
                        .PaddingValues(horizontal = 16.dp),
            ) {
                FilterChip(
                    selected = uiState.filter == FlightListFilter.ACTIVE,
                    onClick = { viewModel.setFilter(FlightListFilter.ACTIVE) },
                    label = { Text(stringResource(R.string.flights_filter_active)) },
                )
                FilterChip(
                    selected = uiState.filter == FlightListFilter.ARCHIVED,
                    onClick = { viewModel.setFilter(FlightListFilter.ARCHIVED) },
                    label = { Text(stringResource(R.string.flights_filter_archived)) },
                )
            }

            if (uiState.flights.isEmpty()) {
                val archived = uiState.filter == FlightListFilter.ARCHIVED
                EmptyState(
                    icon = Icons.Filled.Flight,
                    title =
                        stringResource(
                            if (archived) R.string.flights_empty_archived_title else R.string.flights_empty_title,
                        ),
                    message =
                        stringResource(
                            if (archived) R.string.flights_empty_archived_message else R.string.flights_empty_message,
                        ),
                )
            } else {
                LazyColumn(
                    contentPadding =
                        androidx.compose.foundation.layout
                            .PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(uiState.flights, key = { it.id }) { flight ->
                        FlightCard(flight = flight, onClick = { detailFlightId = flight.id })
                    }
                }
            }
        }
    }

    if (showAddOptions) {
        ModalBottomSheet(onDismissRequest = { showAddOptions = false }) {
            Text(
                text = stringResource(R.string.flights_add_sheet_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.flights_add_manual)) },
                supportingContent = { Text(stringResource(R.string.flights_add_manual_hint)) },
                leadingContent = { Icon(Icons.Filled.EditNote, contentDescription = null) },
                modifier =
                    Modifier.clickable {
                        showAddOptions = false
                        onAddManual()
                    },
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text(stringResource(R.string.flights_add_import)) },
                supportingContent = { Text(stringResource(R.string.flights_add_import_hint)) },
                leadingContent = { Icon(Icons.Filled.QrCodeScanner, contentDescription = null) },
                modifier =
                    Modifier.clickable {
                        showAddOptions = false
                        passPicker.launch(arrayOf("image/*", "application/pdf"))
                    },
            )
            Spacer(modifier = Modifier.padding(bottom = 24.dp))
        }
    }

    val detailFlight = uiState.flights.firstOrNull { it.id == detailFlightId }
    if (detailFlight != null) {
        FlightDetailSheet(
            flight = detailFlight,
            onDismiss = { detailFlightId = null },
            onCheckStatus = {
                detailFlightId = null
                onCheckStatus(detailFlight.id)
            },
            onEdit = {
                detailFlightId = null
                onEdit(detailFlight.id)
            },
            onViewPass = { path ->
                detailFlightId = null
                onViewPass(path)
            },
            onArchiveToggle = {
                val archiving = !detailFlight.archived
                viewModel.setArchived(detailFlight.id, archiving)
                detailFlightId = null
                scope.launch {
                    snackbarHostState.showSnackbar(if (archiving) archivedSnackbar else unarchivedSnackbar)
                }
            },
            onDelete = {
                viewModel.delete(detailFlight.id)
                detailFlightId = null
                scope.launch { snackbarHostState.showSnackbar(deletedSnackbar) }
            },
        )
    }
}

@Composable
private fun FlightCard(
    flight: FlightJourney,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    ClearTravelCard(modifier = Modifier.clickable(onClick = onClick)) {
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
            if (flight.boardingPassPath != null) {
                ExplainableIcon(
                    icon = Icons.AutoMirrored.Filled.AirplaneTicket,
                    explanationRes = R.string.flights_icon_boarding_pass,
                    targetSize = 32.dp,
                    iconSize = 20.dp,
                )
            }
        }
        Text(
            text =
                formatTimestamp(flight.lastFetchedAt)
                    ?.let { context.getString(R.string.flights_last_fetched, it) }
                    ?: stringResource(R.string.flights_never_fetched),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
