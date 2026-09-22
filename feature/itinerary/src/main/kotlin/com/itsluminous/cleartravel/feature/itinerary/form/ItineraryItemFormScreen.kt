package com.itsluminous.cleartravel.feature.itinerary.form

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Train
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.itsluminous.cleartravel.core.designsystem.component.ChipRow
import com.itsluminous.cleartravel.core.designsystem.component.ExplainableIcon
import com.itsluminous.cleartravel.core.model.CommuteMode
import com.itsluminous.cleartravel.core.model.ItineraryItemType
import com.itsluminous.cleartravel.core.model.JourneyType
import com.itsluminous.cleartravel.core.model.PlaceCategory
import com.itsluminous.cleartravel.feature.itinerary.R
import com.itsluminous.cleartravel.feature.itinerary.formatMedium
import com.itsluminous.cleartravel.feature.itinerary.labelRes
import com.itsluminous.cleartravel.feature.itinerary.logic.MapPoint
import com.itsluminous.cleartravel.feature.itinerary.logic.flightJourneyLabel
import com.itsluminous.cleartravel.feature.itinerary.logic.formatLatLng
import com.itsluminous.cleartravel.feature.itinerary.logic.journeyIdFallback
import com.itsluminous.cleartravel.feature.itinerary.logic.parseLatLng
import com.itsluminous.cleartravel.feature.itinerary.logic.trainJourneyLabel
import java.time.LocalTime
import java.util.Locale

/**
 * Add/edit form for one itinerary item: place/commute type toggle, day picker,
 * category or mode chips, planned time, note/link, location (map pin picker AND
 * manual lat,lng entry) and journey linking for commute legs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ItineraryItemFormScreen(
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ItineraryItemFormViewModel = hiltViewModel(),
) {
    val form by viewModel.form.collectAsStateWithLifecycle()
    val dayCount by viewModel.dayCount.collectAsStateWithLifecycle()
    val candidates by viewModel.journeyCandidates.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val saved by viewModel.saved.collectAsStateWithLifecycle()

    var pickingLocation by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var showJourneyPicker by remember { mutableStateOf(false) }
    var latLngText by remember { mutableStateOf("") }

    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    LaunchedEffect(message) {
        val current = message ?: return@LaunchedEffect
        viewModel.consumeMessage()
        snackbarHostState.showSnackbar(context.getString(current.labelRes()))
    }
    LaunchedEffect(saved) {
        if (saved) onDone()
    }
    // ADR-028: runs when the form (re)appears. After a completed journey-add the
    // request is already answered (no-op); after a manual tab tap it is still
    // pending and gets cancelled so nothing is linked here by surprise later.
    LaunchedEffect(Unit) { viewModel.cancelStaleJourneyAdd() }
    // Reflect externally-set coordinates (edit load, map picker) into the text field
    // without clobbering in-progress typing: invalid text clears the coordinates, so
    // the only mismatch left is an external set.
    LaunchedEffect(form.latitude, form.longitude) {
        val lat = form.latitude
        val lng = form.longitude
        if (lat != null && lng != null && parseLatLng(latLngText) != MapPoint(lat, lng)) {
            latLngText = formatLatLng(lat, lng)
        }
    }

    if (pickingLocation) {
        BackHandler { pickingLocation = false }
        LocationPickerView(
            initial = form.latitude?.let { lat -> form.longitude?.let { lng -> MapPoint(lat, lng) } },
            onCancel = { pickingLocation = false },
            onConfirm = { point ->
                viewModel.update { it.copy(latitude = point.latitude, longitude = point.longitude) }
                pickingLocation = false
            },
            modifier = modifier,
        )
        return
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (viewModel.isEdit) {
                                R.string.itinerary_edit_item_title
                            } else {
                                R.string.itinerary_new_item_title
                            },
                        ),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.itinerary_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
        ) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = form.type == ItineraryItemType.PLACE,
                    onClick = { viewModel.update { it.copy(type = ItineraryItemType.PLACE) } },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                ) { Text(stringResource(R.string.itinerary_type_place)) }
                SegmentedButton(
                    selected = form.type == ItineraryItemType.COMMUTE,
                    onClick = { viewModel.update { it.copy(type = ItineraryItemType.COMMUTE) } },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                ) { Text(stringResource(R.string.itinerary_type_commute)) }
            }

            Text(
                text = stringResource(R.string.itinerary_day_picker_label),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
            )
            ChipRow {
                repeat(dayCount) { dayIndex ->
                    FilterChip(
                        selected = form.dayIndex == dayIndex,
                        onClick = { viewModel.update { it.copy(dayIndex = dayIndex) } },
                        label = { Text(stringResource(R.string.itinerary_day_label, dayIndex + 1)) },
                    )
                }
            }

            if (form.type == ItineraryItemType.PLACE) {
                PlaceFields(
                    form = form,
                    latLngText = latLngText,
                    onLatLngTextChange = { text ->
                        latLngText = text
                        val parsed = parseLatLng(text)
                        viewModel.update {
                            it.copy(latitude = parsed?.latitude, longitude = parsed?.longitude)
                        }
                    },
                    onUpdate = viewModel::update,
                    onPickOnMap = { pickingLocation = true },
                    onClearLocation = {
                        latLngText = ""
                        viewModel.update { it.copy(latitude = null, longitude = null) }
                    },
                    onPickTime = { showTimePicker = true },
                )
            } else {
                CommuteFields(
                    form = form,
                    candidates = candidates,
                    onUpdate = viewModel::update,
                    onPickTime = { showTimePicker = true },
                    onLinkJourney = { showJourneyPicker = true },
                    onUnlinkJourney = viewModel::clearLinkedJourney,
                )
            }

            Button(
                onClick = viewModel::save,
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 16.dp),
            ) { Text(stringResource(R.string.itinerary_save)) }
        }
    }

    if (showTimePicker) {
        PlannedTimePickerDialog(
            initial = form.plannedTime,
            onDismiss = { showTimePicker = false },
            onConfirm = { time ->
                viewModel.update { it.copy(plannedTime = time) }
                showTimePicker = false
            },
        )
    }

    if (showJourneyPicker) {
        JourneyPickerSheet(
            candidates = candidates,
            onDismiss = { showJourneyPicker = false },
            onPickTrain = { ticket ->
                viewModel.linkTrain(ticket)
                showJourneyPicker = false
            },
            onPickFlight = { flight ->
                viewModel.linkFlight(flight)
                showJourneyPicker = false
            },
            onAddNew = { type ->
                // The app shell observes the bus and takes the user to the Journeys
                // tab's add flow; this form stays on the Trips back stack and is
                // restored — with the new journey linked — when they are done.
                viewModel.requestJourneyAdd(type)
                showJourneyPicker = false
            },
        )
    }
}

@Composable
private fun PlaceFields(
    form: ItemFormState,
    latLngText: String,
    onLatLngTextChange: (String) -> Unit,
    onUpdate: ((ItemFormState) -> ItemFormState) -> Unit,
    onPickOnMap: () -> Unit,
    onClearLocation: () -> Unit,
    onPickTime: () -> Unit,
) {
    OutlinedTextField(
        value = form.name,
        onValueChange = { value -> onUpdate { it.copy(name = value) } },
        label = { Text(stringResource(R.string.itinerary_item_name_label)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
    )
    Text(
        text = stringResource(R.string.itinerary_sheet_category),
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
    ChipRow {
        PlaceCategory.entries.forEach { category ->
            FilterChip(
                selected = form.category == category,
                onClick = { onUpdate { it.copy(category = category) } },
                label = { Text(stringResource(category.labelRes())) },
            )
        }
    }
    PlannedTimeField(value = form.plannedTime, onUpdate = onUpdate, onPickTime = onPickTime)
    OutlinedTextField(
        value = form.note,
        onValueChange = { value -> onUpdate { it.copy(note = value) } },
        label = { Text(stringResource(R.string.itinerary_note_label)) },
        minLines = 2,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )
    OutlinedTextField(
        value = form.link,
        onValueChange = { value -> onUpdate { it.copy(link = value) } },
        label = { Text(stringResource(R.string.itinerary_link_label)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )

    Text(
        text = stringResource(R.string.itinerary_location_label),
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
    Row(modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = onPickOnMap, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.itinerary_pick_on_map))
        }
        if (form.hasLocation) {
            OutlinedButton(
                onClick = onClearLocation,
                modifier = Modifier.weight(1f).padding(start = 8.dp),
            ) { Text(stringResource(R.string.itinerary_clear_location)) }
        }
    }
    val latLngInvalid = latLngText.isNotBlank() && parseLatLng(latLngText) == null
    OutlinedTextField(
        value = latLngText,
        onValueChange = onLatLngTextChange,
        label = { Text(stringResource(R.string.itinerary_latlng_label)) },
        singleLine = true,
        isError = latLngInvalid,
        supportingText =
            if (latLngInvalid) {
                { Text(stringResource(R.string.itinerary_latlng_invalid)) }
            } else {
                null
            },
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )
}

@Composable
private fun CommuteFields(
    form: ItemFormState,
    candidates: JourneyCandidates,
    onUpdate: ((ItemFormState) -> ItemFormState) -> Unit,
    onPickTime: () -> Unit,
    onLinkJourney: () -> Unit,
    onUnlinkJourney: () -> Unit,
) {
    OutlinedTextField(
        value = form.fromName,
        onValueChange = { value -> onUpdate { it.copy(fromName = value) } },
        label = { Text(stringResource(R.string.itinerary_from_label)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
    )
    OutlinedTextField(
        value = form.toName,
        onValueChange = { value -> onUpdate { it.copy(toName = value) } },
        label = { Text(stringResource(R.string.itinerary_to_label)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )
    Text(
        text = stringResource(R.string.itinerary_sheet_mode),
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
    ChipRow {
        CommuteMode.entries.forEach { mode ->
            FilterChip(
                selected = form.commuteMode == mode,
                onClick = { onUpdate { it.copy(commuteMode = mode) } },
                label = { Text(stringResource(mode.labelRes())) },
            )
        }
    }
    PlannedTimeField(value = form.plannedTime, onUpdate = onUpdate, onPickTime = onPickTime)
    OutlinedTextField(
        value = form.link,
        onValueChange = { value -> onUpdate { it.copy(link = value) } },
        label = { Text(stringResource(R.string.itinerary_link_label)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )

    Text(
        text = stringResource(R.string.itinerary_sheet_linked_journey),
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
    if (form.linkedJourneyId != null && form.linkedJourneyType != null) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text =
                    stringResource(
                        if (form.linkedJourneyType == JourneyType.TRAIN) {
                            R.string.itinerary_linked_train
                        } else {
                            R.string.itinerary_linked_flight
                        },
                        linkedJourneyLabel(form, candidates),
                    ),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f).padding(top = 8.dp),
            )
            ExplainableIcon(
                icon = Icons.Filled.LinkOff,
                explanationRes = R.string.itinerary_unlink_journey,
                onClick = onUnlinkJourney,
            )
        }
    } else {
        OutlinedButton(onClick = onLinkJourney, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.itinerary_link_journey))
        }
    }
}

/** Human label of the linked journey (train number / flight code) with an id fallback. */
private fun linkedJourneyLabel(
    form: ItemFormState,
    candidates: JourneyCandidates,
): String =
    when (form.linkedJourneyType) {
        JourneyType.TRAIN ->
            candidates.trains.firstOrNull { it.id == form.linkedJourneyId }?.let(::trainJourneyLabel)
        JourneyType.FLIGHT ->
            candidates.flights.firstOrNull { it.id == form.linkedJourneyId }?.let(::flightJourneyLabel)
        else -> null
    } ?: journeyIdFallback(form.linkedJourneyId.orEmpty())

@Composable
private fun PlannedTimeField(
    value: String,
    onUpdate: ((ItemFormState) -> ItemFormState) -> Unit,
    onPickTime: () -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { text -> onUpdate { it.copy(plannedTime = text) } },
        label = { Text(stringResource(R.string.itinerary_planned_time_label)) },
        singleLine = true,
        trailingIcon = {
            ExplainableIcon(
                icon = Icons.Filled.Schedule,
                explanationRes = R.string.itinerary_pick_time,
                onClick = onPickTime,
            )
        },
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlannedTimePickerDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val parsed = runCatching { LocalTime.parse(initial) }.getOrNull()
    val state =
        rememberTimePickerState(
            initialHour = parsed?.hour ?: 9,
            initialMinute = parsed?.minute ?: 0,
            is24Hour = true,
        )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.itinerary_planned_time_label)) },
        text = { TimePicker(state = state) },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(String.format(Locale.US, "%02d:%02d", state.hour, state.minute))
                },
            ) { Text(stringResource(R.string.itinerary_ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.itinerary_cancel)) }
        },
    )
}

/**
 * Link-a-journey sheet: "add a new train/flight" actions (ADR-028, hand-off to the
 * Journeys tab) above the existing journeys to pick from.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JourneyPickerSheet(
    candidates: JourneyCandidates,
    onDismiss: () -> Unit,
    onPickTrain: (com.itsluminous.cleartravel.core.model.TrainTicket) -> Unit,
    onPickFlight: (com.itsluminous.cleartravel.core.model.FlightJourney) -> Unit,
    onAddNew: (JourneyType) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
            Text(
                text = stringResource(R.string.itinerary_journey_picker_title),
                style = MaterialTheme.typography.titleLarge,
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.itinerary_add_new_train)) },
                supportingContent = { Text(stringResource(R.string.itinerary_add_new_journey_hint)) },
                leadingContent = { Icon(Icons.Filled.Train, contentDescription = null) },
                modifier = Modifier.clickable { onAddNew(JourneyType.TRAIN) },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.itinerary_add_new_flight)) },
                supportingContent = { Text(stringResource(R.string.itinerary_add_new_journey_hint)) },
                leadingContent = { Icon(Icons.Filled.Flight, contentDescription = null) },
                modifier = Modifier.clickable { onAddNew(JourneyType.FLIGHT) },
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            if (candidates.isEmpty) {
                Text(
                    text = stringResource(R.string.itinerary_journey_picker_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            } else {
                if (candidates.trains.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.itinerary_journey_picker_trains),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    candidates.trains.forEach { ticket ->
                        TextButton(
                            onClick = { onPickTrain(ticket) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text =
                                    listOf(
                                        ticket.trainNumber,
                                        ticket.trainName,
                                        ticket.journeyDate?.formatMedium().orEmpty(),
                                    ).filter { it.isNotBlank() }.joinToString(" · "),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
                if (candidates.flights.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.itinerary_journey_picker_flights),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    candidates.flights.forEach { flight ->
                        TextButton(
                            onClick = { onPickFlight(flight) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text =
                                    listOf(
                                        "${flight.airlineIata} ${flight.flightNumber}",
                                        stringResource(
                                            R.string.itinerary_commute_route,
                                            flight.depAirport,
                                            flight.arrAirport,
                                        ),
                                        flight.date?.formatMedium().orEmpty(),
                                    ).filter { it.isNotBlank() }.joinToString(" · "),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
    }
}
