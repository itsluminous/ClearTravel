package com.itsluminous.cleartravel.ui.intake

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.itsluminous.cleartravel.R

/**
 * "What's this file?" — asks how a shared PDF/image should be imported. While the
 * auto-detect runs a progress line shows; once done the likely type is preselected
 * (and labelled as suggested) but the user always confirms.
 */
@Composable
fun SharedFileIntakeDialog(
    state: SharedFileIntakeUiState,
    onSelect: (SharedDocType) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        modifier = modifier,
        title = { Text(stringResource(R.string.intake_title)) },
        text = {
            Column(modifier = Modifier.selectableGroup()) {
                if (state.detecting) {
                    Text(
                        text = stringResource(R.string.intake_detecting),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
                } else {
                    Text(
                        text = stringResource(R.string.intake_message),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                SharedDocType.entries.forEach { type ->
                    val selected = state.selected == type
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = selected,
                                    enabled = !state.detecting,
                                    role = Role.RadioButton,
                                    onClick = { onSelect(type) },
                                ).padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected, onClick = null, enabled = !state.detecting)
                        Column(modifier = Modifier.padding(start = 12.dp)) {
                            Text(text = stringResource(type.labelRes()), style = MaterialTheme.typography.bodyLarge)
                            if (state.suggested == type) {
                                Text(
                                    text = stringResource(R.string.intake_suggested),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !state.detecting && state.selected != null) {
                Text(stringResource(R.string.intake_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.intake_cancel))
            }
        },
    )
}

private fun SharedDocType.labelRes(): Int =
    when (this) {
        SharedDocType.TRAIN_TICKET -> R.string.intake_option_train_ticket
        SharedDocType.BOARDING_PASS -> R.string.intake_option_boarding_pass
        SharedDocType.BOOKING_CONFIRMATION -> R.string.intake_option_booking_confirmation
    }
