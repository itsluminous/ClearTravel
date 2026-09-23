package com.itsluminous.cleartravel.feature.itinerary.trips

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.itsluminous.cleartravel.core.designsystem.component.ChipRow
import com.itsluminous.cleartravel.core.designsystem.component.InputDialogProperties
import com.itsluminous.cleartravel.core.designsystem.component.LocalDatePickerDialog
import com.itsluminous.cleartravel.core.model.Trip
import com.itsluminous.cleartravel.feature.itinerary.R
import com.itsluminous.cleartravel.feature.itinerary.formatMedium
import com.itsluminous.cleartravel.feature.itinerary.parseCoverColor
import java.time.LocalDate

/** Curated cover emoji choices (a simple row — no full emoji keyboard needed). */
private val TRIP_EMOJIS =
    listOf("🏔️", "🏖️", "🏙️", "🛕", "🏝️", "🎡", "🚆", "✈️", "🏕️", "🌸")

/** Curated cover color choices as stored "#AARRGGBB" values. */
private val TRIP_COLORS =
    listOf(
        "#FF0B57D0",
        "#FFB3261E",
        "#FF146C2E",
        "#FF7D5260",
        "#FF984716",
        "#FF006874",
        "#FF6750A4",
        "#FF5B6300",
    )

/**
 * Add/edit trip dialog: name, destination, date range pickers, curated emoji + color
 * rows. Validation lives in the ViewModel — [onSave] reports the raw form values.
 */
@Composable
internal fun TripFormDialog(
    trip: Trip?,
    onDismiss: () -> Unit,
    onSave: (
        name: String,
        destination: String,
        startDate: LocalDate?,
        endDate: LocalDate?,
        coverEmoji: String,
        coverColor: String,
    ) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(trip?.name.orEmpty()) }
    var destination by rememberSaveable { mutableStateOf(trip?.destination.orEmpty()) }
    var startDate by remember { mutableStateOf(trip?.startDate) }
    var endDate by remember { mutableStateOf(trip?.endDate) }
    var emoji by rememberSaveable { mutableStateOf(trip?.coverEmoji?.ifBlank { TRIP_EMOJIS.first() } ?: TRIP_EMOJIS.first()) }
    var color by rememberSaveable { mutableStateOf(trip?.coverColor?.ifBlank { TRIP_COLORS.first() } ?: TRIP_COLORS.first()) }
    var showStartPicker by remember { mutableStateOf(false) }
    var showEndPicker by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = InputDialogProperties,
        title = {
            Text(
                stringResource(
                    if (trip == null) R.string.itinerary_new_trip_title else R.string.itinerary_edit_trip_title,
                ),
            )
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.itinerary_trip_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = destination,
                    onValueChange = { destination = it },
                    label = { Text(stringResource(R.string.itinerary_trip_destination_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                Row(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    OutlinedButton(
                        onClick = { showStartPicker = true },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            text =
                                startDate?.formatMedium()
                                    ?: stringResource(R.string.itinerary_trip_start_date),
                            maxLines = 1,
                        )
                    }
                    OutlinedButton(
                        onClick = { showEndPicker = true },
                        modifier = Modifier.weight(1f).padding(start = 8.dp),
                    ) {
                        Text(
                            text =
                                endDate?.formatMedium()
                                    ?: stringResource(R.string.itinerary_trip_end_date),
                            maxLines = 1,
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.itinerary_cover_emoji_label),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                )
                ChipRow {
                    TRIP_EMOJIS.forEach { candidate ->
                        FilterChip(
                            selected = emoji == candidate,
                            onClick = { emoji = candidate },
                            label = { Text(candidate) },
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.itinerary_cover_color_label),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                )
                ChipRow {
                    TRIP_COLORS.forEach { candidate ->
                        ColorSwatch(
                            colorHex = candidate,
                            selected = color == candidate,
                            onSelect = { color = candidate },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name, destination, startDate, endDate, emoji, color) }) {
                Text(stringResource(R.string.itinerary_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.itinerary_cancel)) }
        },
    )

    if (showStartPicker) {
        LocalDatePickerDialog(
            initial = startDate,
            onConfirm = { picked ->
                startDate = picked
                showStartPicker = false
            },
            onDismiss = { showStartPicker = false },
        )
    }
    if (showEndPicker) {
        LocalDatePickerDialog(
            initial = endDate,
            onConfirm = { picked ->
                endDate = picked
                showEndPicker = false
            },
            onDismiss = { showEndPicker = false },
        )
    }
}

@Composable
private fun ColorSwatch(
    colorHex: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    val swatchColor = parseCoverColor(colorHex) ?: MaterialTheme.colorScheme.primary
    val optionLabel = stringResource(R.string.itinerary_cover_color_option)
    Box(
        modifier =
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(swatchColor)
                .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
                .semantics { contentDescription = optionLabel },
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Box(
                modifier =
                    Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(Color.White),
            )
        }
    }
}
