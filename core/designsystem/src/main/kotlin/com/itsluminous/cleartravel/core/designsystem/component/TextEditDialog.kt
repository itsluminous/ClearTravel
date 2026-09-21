package com.itsluminous.cleartravel.core.designsystem.component

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.ImeAction

/**
 * Small single-field edit dialog (rename an item, retitle a row). All labels are passed
 * in as already-resolved strings so feature modules keep ownership of their resources.
 * Confirm is disabled while the trimmed text is blank; both the confirm button and the
 * keyboard's done action submit the trimmed value.
 */
@Composable
fun TextEditDialog(
    title: String,
    label: String,
    initialValue: String,
    confirmText: String,
    dismissText: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by rememberSaveable(initialValue) { mutableStateOf(initialValue) }
    val trimmed = text.trim()
    val submit = { if (trimmed.isNotEmpty()) onConfirm(trimmed) }
    AlertDialog(
        modifier = modifier,
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(label) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
            )
        },
        confirmButton = {
            TextButton(onClick = submit, enabled = trimmed.isNotEmpty()) {
                Text(confirmText)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(dismissText)
            }
        },
    )
}
