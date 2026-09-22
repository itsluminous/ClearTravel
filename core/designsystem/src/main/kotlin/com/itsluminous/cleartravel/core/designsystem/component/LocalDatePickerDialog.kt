package com.itsluminous.cleartravel.core.designsystem.component

import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.itsluminous.cleartravel.core.designsystem.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The one Material 3 [DatePickerDialog] wrapper over [LocalDate] — every date field in
 * the app (train journey date, flight date, trip start/end, document expiry) opens this
 * instead of re-implementing the millis ↔ `LocalDate` conversion and the OK/Cancel
 * buttons. The picker works in UTC days, so the conversion is pinned to [ZoneOffset.UTC]
 * in both directions; [initial] pre-selects a day (nothing selected when null).
 *
 * [onConfirm] fires only when a day is selected; confirming with nothing selected
 * behaves like Cancel ([onDismiss]). The caller owns the "is the dialog shown" state.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalDatePickerDialog(
    initial: LocalDate?,
    onConfirm: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    val pickerState =
        rememberDatePickerState(
            initialSelectedDateMillis = initial?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli(),
        )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val picked = pickerState.selectedDateMillis?.let(::localDateFromPickerMillis)
                    if (picked != null) onConfirm(picked) else onDismiss()
                },
            ) { Text(stringResource(R.string.designsystem_date_picker_ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.designsystem_date_picker_cancel)) }
        },
    ) {
        DatePicker(state = pickerState)
    }
}

/** The picker reports UTC-midnight millis for the selected day; pure and unit-tested. */
fun localDateFromPickerMillis(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
