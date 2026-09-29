package com.itsluminous.cleartravel.core.designsystem.component

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/**
 * Multi-line "paste text here" dialog used by the SMS/email prefill paths (train
 * tickets, flights — ADR-036: shared, not copied per feature). All labels are passed in
 * as already-resolved strings so feature modules keep ownership of their resources.
 * Confirm is disabled while the text is blank. Uses [InputDialogProperties] (ADR-041):
 * an outside tap never throws the pasted text away.
 */
@Composable
fun PasteTextDialog(
    title: String,
    label: String,
    confirmText: String,
    dismissText: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = InputDialogProperties,
        modifier = modifier,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(label) },
                minLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) {
                AutoShrinkText(confirmText)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                AutoShrinkText(dismissText)
            }
        },
    )
}
