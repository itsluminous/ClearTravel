package com.itsluminous.cleartravel.feature.menu

import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.itsluminous.cleartravel.core.data.backup.BackupEntries
import com.itsluminous.cleartravel.core.data.backup.ImportPreview
import com.itsluminous.cleartravel.core.designsystem.component.ClearTravelCard
import com.itsluminous.cleartravel.core.designsystem.component.ExplainableIcon
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private const val BACKUP_MIME_TYPE = "application/zip"

/** Accept any file on import — SAF providers label ZIPs inconsistently. */
private val IMPORT_MIME_TYPES = arrayOf("application/zip", "application/octet-stream")

/**
 * Menu → Backup & Restore: export to a user-picked location (SAF CreateDocument),
 * import with a preview-confirm dialog (SAF OpenDocument), last-backup info and
 * per-row merge accounting in the result snackbar (ADR-015).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BackupRestoreScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BackupRestoreViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    val exportLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(BACKUP_MIME_TYPE)) { uri ->
            if (uri != null) viewModel.export(uri)
        }
    val importLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) viewModel.requestImport(uri)
        }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is BackupRestoreEvent.ExportDone ->
                    snackbarHostState.showSnackbar(
                        context.getString(R.string.menu_backup_export_done, event.totalRows),
                    )
                is BackupRestoreEvent.ImportDone ->
                    snackbarHostState.showSnackbar(
                        context.getString(
                            R.string.menu_backup_import_done,
                            event.summary.inserted,
                            event.summary.updated,
                            event.summary.skipped,
                        ),
                    )
                BackupRestoreEvent.BackupVersionTooNew ->
                    snackbarHostState.showSnackbar(context.getString(R.string.menu_backup_error_version))
                BackupRestoreEvent.BackupUnreadable ->
                    snackbarHostState.showSnackbar(context.getString(R.string.menu_backup_error_unreadable))
                BackupRestoreEvent.IoFailed ->
                    snackbarHostState.showSnackbar(context.getString(R.string.menu_backup_error_io))
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.menu_backup_title)) },
                navigationIcon = {
                    ExplainableIcon(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        explanationRes = R.string.menu_back,
                        onClick = onBack,
                    )
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
            if (uiState.inProgress) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    text = stringResource(R.string.menu_backup_in_progress),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            ClearTravelCard {
                Text(
                    text = stringResource(R.string.menu_backup_export_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.menu_backup_export_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                val lastBackup = uiState.lastBackup
                Text(
                    text =
                        if (lastBackup == null) {
                            stringResource(R.string.menu_backup_last_backup_never)
                        } else {
                            stringResource(
                                R.string.menu_backup_last_backup,
                                formatInstant(lastBackup.createdAt),
                                Formatter.formatShortFileSize(context, lastBackup.sizeBytes),
                            )
                        },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Button(
                    onClick = { exportLauncher.launch(viewModel.suggestedExportFileName()) },
                    enabled = !uiState.inProgress,
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Text(stringResource(R.string.menu_backup_export_action))
                }
            }

            ClearTravelCard {
                Text(
                    text = stringResource(R.string.menu_backup_import_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.menu_backup_import_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Button(
                    onClick = { importLauncher.launch(IMPORT_MIME_TYPES) },
                    enabled = !uiState.inProgress,
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Text(stringResource(R.string.menu_backup_import_action))
                }
            }
        }
    }

    uiState.pendingImport?.let { pending ->
        ImportConfirmDialog(
            preview = pending.preview,
            onConfirm = viewModel::confirmImport,
            onDismiss = viewModel::dismissImport,
        )
    }
}

/** Confirmation before merging: backup date + record counts (spec feature 6). */
@Composable
private fun ImportConfirmDialog(
    preview: ImportPreview,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val journeys =
        preview.count(BackupEntries.KEY_TRAIN_TICKETS) + preview.count(BackupEntries.KEY_FLIGHT_JOURNEYS)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.menu_backup_import_confirm_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(
                        R.string.menu_backup_import_confirm_message,
                        formatInstant(preview.createdAt),
                        preview.totalRows,
                    ),
                )
                Text(
                    stringResource(
                        R.string.menu_backup_import_confirm_counts,
                        preview.count(BackupEntries.KEY_TRIPS),
                        journeys,
                        preview.count(BackupEntries.KEY_CHECKLISTS),
                        preview.count(BackupEntries.KEY_ATTACHMENTS),
                    ),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.menu_backup_import_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.menu_cancel)) }
        },
    )
}

private fun ImportPreview.count(key: String): Int = entityCounts[key] ?: 0

private fun formatInstant(instant: Instant): String =
    DateTimeFormatter
        .ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withZone(ZoneId.systemDefault())
        .format(instant)
