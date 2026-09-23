package com.itsluminous.cleartravel.ui.share

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.itsluminous.cleartravel.R
import com.itsluminous.cleartravel.core.data.share.ShareImportPreview
import com.itsluminous.cleartravel.core.data.share.ShareImportResult
import com.itsluminous.cleartravel.core.data.share.ShareLinkError

/**
 * Hosts the shared trip/checklist import (ADR-039): starts the preview when a
 * [request] arrives, shows the confirm dialog ("Add trip …?" / "Update your existing
 * checklist …?"), the explanatory failure dialog for [failure], and reports the
 * written result through [onDone] so the shell can land on it.
 */
@Composable
fun ShareImportHost(
    viewModel: ShareImportViewModel,
    request: ShareImportRequest?,
    failure: ShareLinkError?,
    onDone: (ShareImportResult) -> Unit,
    onDismissed: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(request) {
        if (request != null) viewModel.start(request)
    }
    LaunchedEffect(failure) {
        if (failure != null) viewModel.fail(failure)
    }
    LaunchedEffect(state) {
        val done = state as? ShareImportUiState.Done ?: return@LaunchedEffect
        onDone(done.result)
        viewModel.reset()
    }

    fun dismiss() {
        viewModel.reset()
        onDismissed()
    }

    when (val current = state) {
        ShareImportUiState.Idle, is ShareImportUiState.Done -> Unit
        ShareImportUiState.Loading, ShareImportUiState.Importing ->
            AlertDialog(
                onDismissRequest = {},
                title = { Text(stringResource(R.string.share_import_working_title)) },
                text = { LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) },
                confirmButton = {},
            )
        is ShareImportUiState.Confirm ->
            ConfirmDialog(preview = current.preview, onConfirm = viewModel::confirm, onCancel = ::dismiss)
        is ShareImportUiState.Failed ->
            AlertDialog(
                onDismissRequest = ::dismiss,
                title = { Text(stringResource(R.string.share_import_failed_title)) },
                text = {
                    Text(
                        stringResource(
                            when (current.error) {
                                ShareLinkError.CORRUPTED -> R.string.share_import_failed_corrupted
                                ShareLinkError.UNSUPPORTED_VERSION -> R.string.share_import_failed_version
                                ShareLinkError.INVALID_CONTENT -> R.string.share_import_failed_invalid
                            },
                        ),
                    )
                },
                confirmButton = {
                    TextButton(onClick = ::dismiss) { Text(stringResource(R.string.share_import_ok)) }
                },
            )
    }
}

@Composable
private fun ConfirmDialog(
    preview: ShareImportPreview,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val title =
        when (preview) {
            is ShareImportPreview.Trip ->
                stringResource(if (preview.existing) R.string.share_import_trip_update_title else R.string.share_import_trip_add_title)
            is ShareImportPreview.Checklist ->
                stringResource(
                    if (preview.existing) R.string.share_import_checklist_update_title else R.string.share_import_checklist_add_title,
                )
        }
    val message =
        when (preview) {
            is ShareImportPreview.Trip -> {
                val places = pluralStringResource(R.plurals.share_import_places, preview.itemCount, preview.itemCount)
                if (preview.existing) {
                    stringResource(R.string.share_import_trip_update_message, preview.name, places)
                } else {
                    stringResource(R.string.share_import_trip_add_message, preview.name, places)
                }
            }
            is ShareImportPreview.Checklist -> {
                val items = pluralStringResource(R.plurals.share_import_items, preview.itemCount, preview.itemCount)
                if (preview.existing) {
                    stringResource(R.string.share_import_checklist_update_message, preview.name, items)
                } else {
                    stringResource(R.string.share_import_checklist_add_message, preview.name, items)
                }
            }
        }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(if (preview.existing) R.string.share_import_update else R.string.share_import_add))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.share_import_cancel)) }
        },
    )
}
