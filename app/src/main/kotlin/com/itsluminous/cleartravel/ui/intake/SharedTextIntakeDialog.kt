package com.itsluminous.cleartravel.ui.intake

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.itsluminous.cleartravel.R
import com.itsluminous.cleartravel.core.data.intake.SharedTextKind

/**
 * "What's this text?" — asks whether a shared SMS/email is a train ticket or a flight
 * (ADR-042). Same look and pattern as [SharedFileIntakeDialog]: the classifier's pick
 * is preselected and labelled as suggested, the user always confirms. A short preview
 * of the shared text shows what is being decided about. A pure choice, so the default
 * tap-outside dismissal stays (ADR-041 — nothing typed is lost).
 */
@Composable
fun SharedTextIntakeDialog(
    state: SharedTextIntakeUiState,
    onSelect: (SharedTextKind) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        modifier = modifier,
        title = { Text(stringResource(R.string.intake_text_title)) },
        text = {
            Column(modifier = Modifier.selectableGroup()) {
                Text(
                    text = state.text.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                Text(
                    text = stringResource(R.string.intake_text_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                SharedTextKind.entries.forEach { kind ->
                    val selected = state.selected == kind
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = selected,
                                    role = Role.RadioButton,
                                    onClick = { onSelect(kind) },
                                ).padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected, onClick = null)
                        Column(modifier = Modifier.padding(start = 12.dp)) {
                            Text(text = stringResource(kind.labelRes()), style = MaterialTheme.typography.bodyLarge)
                            if (state.suggested == kind) {
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
            TextButton(onClick = onConfirm, enabled = state.selected != null) {
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

private fun SharedTextKind.labelRes(): Int =
    when (this) {
        SharedTextKind.TRAIN -> R.string.intake_option_train_ticket
        SharedTextKind.FLIGHT -> R.string.intake_option_flight
    }
