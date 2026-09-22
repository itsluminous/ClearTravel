package com.itsluminous.cleartravel.feature.flights.form

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.itsluminous.cleartravel.core.designsystem.component.ExplainableIcon
import com.itsluminous.cleartravel.core.designsystem.component.LocalDatePickerDialog
import com.itsluminous.cleartravel.core.ocr.ExtractionConfidence
import com.itsluminous.cleartravel.core.ocr.model.BoardingPassSource
import com.itsluminous.cleartravel.core.ocr.model.BookingConfirmationSource
import com.itsluminous.cleartravel.feature.flights.R
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlightFormScreen(
    editId: String?,
    importUri: String?,
    onClose: () -> Unit,
    onSavedAndCheck: (flightId: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FlightFormViewModel = hiltViewModel(),
    /** Picked booking-confirmation file (third add path, ADR-017); defaulted so existing call sites are untouched. */
    bookingUri: String? = null,
    /** Plain "Save" completion with the journey id; defaults to [onClose] so existing call sites are untouched. */
    onSaved: (flightId: String) -> Unit = { onClose() },
    /**
     * Save refused as a duplicate (ADR-025): nothing was written, [existingFlightId]
     * is the journey already carrying this airline + number + date. Hosts show a
     * notice with a "View" action; defaulted so existing call sites are untouched.
     */
    onDuplicate: (existingFlightId: String) -> Unit = {},
) {
    val state by viewModel.formState.collectAsStateWithLifecycle()
    val busy by viewModel.isBusy.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is FlightFormEvent.DuplicateFlight -> onDuplicate(event.existingFlightId)
            }
        }
    }

    LaunchedEffect(editId, importUri, bookingUri) {
        when {
            editId != null -> viewModel.startEdit(editId)
            importUri != null -> viewModel.startFromBoardingPass(importUri)
            bookingUri != null -> viewModel.startFromBookingConfirmation(bookingUri)
            else -> viewModel.startBlank()
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (state.isEdit) R.string.flights_form_title_edit else R.string.flights_form_title_add,
                        ),
                    )
                },
                navigationIcon = {
                    ExplainableIcon(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        explanationRes = R.string.flights_icon_back,
                        onClick = onClose,
                    )
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (busy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                if (state.pendingPassUri != null && state.prefillSource == BoardingPassSource.NONE) {
                    Text(
                        text = stringResource(R.string.flights_form_importing),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (state.pendingBookingUri != null && state.bookingSource == BookingConfirmationSource.NONE) {
                    Text(
                        text = stringResource(R.string.flights_form_importing_booking),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            PrefillBanner(state)

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FormField(
                    value = state.airlineIata,
                    onChange = { value -> viewModel.update { it.copy(airlineIata = value.uppercase()) } },
                    labelRes = R.string.flights_field_airline,
                    error = FlightFormError.AIRLINE_INVALID in state.errors,
                    errorRes = R.string.flights_error_airline,
                    confidence = state.confidences[FlightField.AIRLINE],
                    modifier = Modifier.weight(1f),
                )
                FormField(
                    value = state.flightNumber,
                    onChange = { value -> viewModel.update { it.copy(flightNumber = value) } },
                    labelRes = R.string.flights_field_flight_number,
                    error = FlightFormError.FLIGHT_NUMBER_INVALID in state.errors,
                    errorRes = R.string.flights_error_flight_number,
                    confidence = state.confidences[FlightField.FLIGHT_NUMBER],
                    modifier = Modifier.weight(1f),
                )
            }
            // Date comes from a picker, not typing (user request 2026-09-22): the field
            // is read-only and mirrors the picked ISO date; a tap anywhere on it opens
            // the shared picker.
            var showDatePicker by rememberSaveable { mutableStateOf(false) }
            FormField(
                value = state.dateText,
                onChange = {},
                labelRes = R.string.flights_field_date,
                error = FlightFormError.DATE_INVALID in state.errors,
                errorRes = R.string.flights_error_date,
                confidence = state.confidences[FlightField.DATE],
                onClickReadOnly = { showDatePicker = true },
            )
            if (showDatePicker) {
                LocalDatePickerDialog(
                    initial = runCatching { LocalDate.parse(state.dateText) }.getOrNull(),
                    onConfirm = { picked ->
                        viewModel.update { it.copy(dateText = picked.toString()) }
                        showDatePicker = false
                    },
                    onDismiss = { showDatePicker = false },
                )
            }
            FormField(
                value = state.pnr,
                onChange = { value -> viewModel.update { it.copy(pnr = value) } },
                labelRes = R.string.flights_field_pnr,
                confidence = state.confidences[FlightField.PNR],
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FormField(
                    value = state.seat,
                    onChange = { value -> viewModel.update { it.copy(seat = value) } },
                    labelRes = R.string.flights_field_seat,
                    confidence = state.confidences[FlightField.SEAT],
                    modifier = Modifier.weight(1f),
                )
                FormField(
                    value = state.cabinClass,
                    onChange = { value -> viewModel.update { it.copy(cabinClass = value) } },
                    labelRes = R.string.flights_field_cabin,
                    confidence = state.confidences[FlightField.CABIN],
                    modifier = Modifier.weight(1f),
                )
            }

            Text(
                text = stringResource(R.string.flights_form_route_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FormField(
                    value = state.depAirport,
                    onChange = { value -> viewModel.update { it.copy(depAirport = value.uppercase()) } },
                    labelRes = R.string.flights_field_dep_airport,
                    confidence = state.confidences[FlightField.DEP_AIRPORT],
                    modifier = Modifier.weight(1f),
                )
                FormField(
                    value = state.arrAirport,
                    onChange = { value -> viewModel.update { it.copy(arrAirport = value.uppercase()) } },
                    labelRes = R.string.flights_field_arr_airport,
                    confidence = state.confidences[FlightField.ARR_AIRPORT],
                    modifier = Modifier.weight(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FormField(
                    value = state.depTimeText,
                    onChange = { value -> viewModel.update { it.copy(depTimeText = value) } },
                    labelRes = R.string.flights_field_dep_time,
                    error = FlightFormError.DEP_TIME_INVALID in state.errors,
                    errorRes = R.string.flights_error_dep_time,
                    modifier = Modifier.weight(1f),
                )
                FormField(
                    value = state.arrTimeText,
                    onChange = { value -> viewModel.update { it.copy(arrTimeText = value) } },
                    labelRes = R.string.flights_field_arr_time,
                    error = FlightFormError.ARR_TIME_INVALID in state.errors,
                    errorRes = R.string.flights_error_arr_time,
                    modifier = Modifier.weight(1f),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = { viewModel.save(onSaved) },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.flights_form_save)) }
                Button(
                    onClick = { viewModel.save(onSavedAndCheck) },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.flights_form_save_fetch)) }
            }
        }
    }
}

/** Source indicator: barcode vs OCR vs nothing recognized (ADR-009 review-first). */
@Composable
private fun PrefillBanner(state: FlightFormState) {
    if (state.pendingPassUri != null) {
        val textRes =
            when (state.prefillSource) {
                BoardingPassSource.BARCODE -> R.string.flights_prefill_source_barcode
                BoardingPassSource.OCR_TEXT -> R.string.flights_prefill_source_ocr
                BoardingPassSource.NONE -> R.string.flights_prefill_source_none
            }
        Text(
            text = stringResource(textRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    if (state.pendingBookingUri != null) {
        val textRes =
            when (state.bookingSource) {
                BookingConfirmationSource.BARCODE -> R.string.flights_booking_source_barcode
                BookingConfirmationSource.OCR_TEXT -> R.string.flights_booking_source_ocr
                BookingConfirmationSource.NONE -> R.string.flights_booking_source_none
            }
        Text(
            text = stringResource(textRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        if (state.returnLegHint) {
            Text(
                text = stringResource(R.string.flights_booking_return_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}

/**
 * One form field. With [onClickReadOnly] the field is read-only and a tap on it (press
 * released inside) invokes the callback — the idiomatic picker-backed text field: the
 * interaction source of the field itself is observed, so there is no overlay and the
 * label, error and supporting text keep their normal semantics.
 */
@Composable
private fun FormField(
    value: String,
    onChange: (String) -> Unit,
    labelRes: Int,
    modifier: Modifier = Modifier,
    error: Boolean = false,
    errorRes: Int? = null,
    confidence: ExtractionConfidence? = null,
    onClickReadOnly: (() -> Unit)? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    if (onClickReadOnly != null) {
        LaunchedEffect(interactionSource) {
            interactionSource.interactions.collect { interaction ->
                if (interaction is PressInteraction.Release) onClickReadOnly()
            }
        }
    }
    OutlinedTextField(
        value = value,
        readOnly = onClickReadOnly != null,
        interactionSource = interactionSource,
        onValueChange = onChange,
        label = { Text(stringResource(labelRes)) },
        isError = error,
        supportingText = {
            when {
                error && errorRes != null -> Text(stringResource(errorRes))
                confidence != null && confidence != ExtractionConfidence.NONE ->
                    Text(stringResource(confidence.labelRes()))
            }
        },
        singleLine = true,
        modifier = modifier.fillMaxWidth(),
    )
}

private fun ExtractionConfidence.labelRes(): Int =
    when (this) {
        ExtractionConfidence.HIGH -> R.string.flights_confidence_high
        ExtractionConfidence.MEDIUM -> R.string.flights_confidence_medium
        ExtractionConfidence.LOW -> R.string.flights_confidence_low
        ExtractionConfidence.NONE -> R.string.flights_confidence_low
    }
