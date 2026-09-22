package com.itsluminous.cleartravel.feature.documents

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Event
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
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
import com.itsluminous.cleartravel.core.designsystem.component.ChipRow
import com.itsluminous.cleartravel.core.designsystem.component.ExplainableIcon
import com.itsluminous.cleartravel.core.model.TravelDocumentType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** What the details dialog hands back on Save. */
data class DocumentDetails(
    val type: TravelDocumentType,
    /** Trimmed; empty means "use the type preset label". */
    val name: String,
    val expiryDate: LocalDate?,
    val note: String,
)

/**
 * Add/edit dialog (ADR-027): type preset chips, a name field pre-filled from the
 * selected preset (edited names stick when the type changes), an optional expiry date
 * via the Material date picker, and an optional note. [initial] null = add mode.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DocumentDetailsDialog(
    initial: DocumentDetails?,
    onDismiss: () -> Unit,
    onSave: (DocumentDetails) -> Unit,
) {
    var type by rememberSaveable { mutableStateOf(initial?.type ?: TravelDocumentType.PASSPORT) }
    var name by rememberSaveable { mutableStateOf(initial?.name.orEmpty()) }
    var nameEdited by rememberSaveable { mutableStateOf(initial != null) }
    var expiryIso by rememberSaveable { mutableStateOf(initial?.expiryDate?.toString()) }
    var note by rememberSaveable { mutableStateOf(initial?.note.orEmpty()) }
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    val expiryDate = expiryIso?.let(LocalDate::parse)

    val presetLabel = stringResource(DocumentTypePresets.labelRes(type))
    // Add mode: the name tracks the preset until the user types.
    val displayedName = if (nameEdited) name else presetLabel

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (initial == null) R.string.documents_add_title else R.string.documents_edit_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = stringResource(R.string.documents_type_label),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                ChipRow {
                    DocumentTypePresets.ordered.forEach { preset ->
                        FilterChip(
                            selected = preset == type,
                            onClick = { type = preset },
                            label = { Text(stringResource(DocumentTypePresets.labelRes(preset))) },
                        )
                    }
                }
                OutlinedTextField(
                    value = displayedName,
                    onValueChange = {
                        nameEdited = true
                        name = it
                    },
                    label = { Text(stringResource(R.string.documents_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.documents_expiry_label),
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Text(
                            text = expiryDate?.let(DocumentExpiry::format) ?: stringResource(R.string.documents_expiry_none),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                    if (expiryDate != null) {
                        ExplainableIcon(
                            icon = Icons.Filled.Clear,
                            explanationRes = R.string.documents_expiry_clear,
                            onClick = { expiryIso = null },
                        )
                    }
                    ExplainableIcon(
                        icon = Icons.Filled.Event,
                        explanationRes = R.string.documents_expiry_pick,
                        tint = MaterialTheme.colorScheme.primary,
                        onClick = { showDatePicker = true },
                    )
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text(stringResource(R.string.documents_note_label)) },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        DocumentDetails(
                            type = type,
                            name = displayedName.trim(),
                            expiryDate = expiryDate,
                            note = note,
                        ),
                    )
                },
                enabled = displayedName.isNotBlank(),
            ) {
                Text(stringResource(R.string.documents_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.documents_cancel))
            }
        },
    )

    if (showDatePicker) {
        val pickerState =
            rememberDatePickerState(
                initialSelectedDateMillis =
                    (expiryDate ?: LocalDate.now().plusYears(1))
                        .atStartOfDay(ZoneOffset.UTC)
                        .toInstant()
                        .toEpochMilli(),
            )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let { millis ->
                            expiryIso =
                                Instant
                                    .ofEpochMilli(millis)
                                    .atZone(ZoneOffset.UTC)
                                    .toLocalDate()
                                    .toString()
                        }
                        showDatePicker = false
                    },
                ) {
                    Text(stringResource(R.string.documents_date_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text(stringResource(R.string.documents_cancel))
                }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }
}
