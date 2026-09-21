package com.itsluminous.cleartravel.feature.trains.form

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.itsluminous.cleartravel.core.designsystem.component.AutoShrinkText
import com.itsluminous.cleartravel.core.designsystem.component.ExplainableIcon
import com.itsluminous.cleartravel.feature.trains.R
import com.itsluminous.cleartravel.feature.trains.list.formatDate
import java.time.Instant
import java.time.ZoneOffset

/**
 * The add/edit ticket form — the single review point for every prefill path
 * (manual, pasted SMS/email, imported PDF/image). Low-confidence auto-filled fields
 * carry a subtle supporting-text marker; nothing is saved without the user's tap.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TrainTicketFormScreen(
    state: TrainFormUiState,
    viewModel: TrainTicketFormViewModel,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDatePicker by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ExplainableIcon(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                explanationRes = R.string.trains_form_back,
                onClick = onCancel,
            )
            Text(
                text =
                    stringResource(
                        if (state.isEdit) R.string.trains_form_title_edit else R.string.trains_form_title_add,
                    ),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        if (state.prefilled) {
            Text(
                text = stringResource(R.string.trains_form_prefilled),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }

        FormField(
            value = state.pnr,
            onValueChange = viewModel::onPnrChange,
            labelRes = R.string.trains_form_pnr,
            isError = state.pnrError,
            errorRes = R.string.trains_form_pnr_error,
            lowConfidence = state.lowConfidence(TrainFormField.PNR),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FormField(
                value = state.trainNumber,
                onValueChange = viewModel::onTrainNumberChange,
                labelRes = R.string.trains_form_train_number,
                lowConfidence = state.lowConfidence(TrainFormField.TRAIN_NUMBER),
                modifier = Modifier.weight(1f),
            )
            FormField(
                value = state.trainName,
                onValueChange = viewModel::onTrainNameChange,
                labelRes = R.string.trains_form_train_name,
                lowConfidence = state.lowConfidence(TrainFormField.TRAIN_NAME),
                modifier = Modifier.weight(1f),
            )
        }
        OutlinedButton(onClick = { showDatePicker = true }, modifier = Modifier.fillMaxWidth()) {
            Text(
                text =
                    state.journeyDate?.let(::formatDate)
                        ?: stringResource(R.string.trains_form_pick_date),
            )
        }
        if (state.lowConfidence(TrainFormField.JOURNEY_DATE)) {
            LowConfidenceMarker()
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FormField(
                value = state.fromStation,
                onValueChange = viewModel::onFromStationChange,
                labelRes = R.string.trains_form_from_station,
                lowConfidence = state.lowConfidence(TrainFormField.FROM_STATION),
                modifier = Modifier.weight(1f),
            )
            FormField(
                value = state.toStation,
                onValueChange = viewModel::onToStationChange,
                labelRes = R.string.trains_form_to_station,
                lowConfidence = state.lowConfidence(TrainFormField.TO_STATION),
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FormField(
                value = state.travelClass,
                onValueChange = viewModel::onTravelClassChange,
                labelRes = R.string.trains_form_class,
                lowConfidence = state.lowConfidence(TrainFormField.TRAVEL_CLASS),
                modifier = Modifier.weight(1f),
            )
            FormField(
                value = state.quota,
                onValueChange = viewModel::onQuotaChange,
                labelRes = R.string.trains_form_quota,
                lowConfidence = state.lowConfidence(TrainFormField.QUOTA),
                modifier = Modifier.weight(1f),
            )
        }

        Text(
            text = stringResource(R.string.trains_form_passengers),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
        state.passengers.forEach { row ->
            PassengerRowEditor(
                row = row,
                onUpdate = { transform -> viewModel.updatePassengerRow(row.rowKey, transform) },
                onRemove = { viewModel.removePassengerRow(row.rowKey) },
            )
        }
        OutlinedButton(onClick = viewModel::addPassengerRow) {
            Text(stringResource(R.string.trains_form_add_passenger))
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 24.dp),
        ) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.trains_form_cancel))
            }
            Button(
                onClick = viewModel::save,
                enabled = !state.saving,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.trains_form_save))
            }
        }
    }

    if (showDatePicker) {
        val pickerState =
            rememberDatePickerState(
                initialSelectedDateMillis =
                    state.journeyDate
                        ?.atStartOfDay(ZoneOffset.UTC)
                        ?.toInstant()
                        ?.toEpochMilli(),
            )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let { millis ->
                            viewModel.onJourneyDateChange(
                                Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate(),
                            )
                        }
                        showDatePicker = false
                    },
                ) {
                    Text(stringResource(R.string.trains_form_date_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text(stringResource(R.string.trains_form_date_cancel))
                }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

@Composable
private fun FormField(
    value: String,
    onValueChange: (String) -> Unit,
    labelRes: Int,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    errorRes: Int? = null,
    lowConfidence: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(labelRes)) },
        isError = isError,
        supportingText =
            when {
                isError && errorRes != null -> {
                    { Text(stringResource(errorRes)) }
                }
                lowConfidence -> {
                    { LowConfidenceMarker() }
                }
                else -> null
            },
        singleLine = true,
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun LowConfidenceMarker(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.trains_form_low_confidence),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.tertiary,
        modifier = modifier,
    )
}

@Composable
private fun PassengerRowEditor(
    row: PassengerFormRow,
    onUpdate: ((PassengerFormRow) -> PassengerFormRow) -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = row.name,
                onValueChange = { value -> onUpdate { it.copy(name = value) } },
                label = { Text(stringResource(R.string.trains_form_passenger_name)) },
                supportingText =
                    if (row.lowConfidence) {
                        { LowConfidenceMarker() }
                    } else {
                        null
                    },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            ExplainableIcon(
                icon = Icons.Filled.Delete,
                explanationRes = R.string.trains_form_remove_passenger,
                onClick = onRemove,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = row.coach,
                onValueChange = { value -> onUpdate { it.copy(coach = value) } },
                label = { AutoShrinkText(stringResource(R.string.trains_form_passenger_coach)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = row.seatBerth,
                onValueChange = { value -> onUpdate { it.copy(seatBerth = value) } },
                label = { AutoShrinkText(stringResource(R.string.trains_form_passenger_seat)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = row.bookingStatus,
                onValueChange = { value -> onUpdate { it.copy(bookingStatus = value) } },
                label = { AutoShrinkText(stringResource(R.string.trains_form_passenger_status)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
