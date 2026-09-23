package com.itsluminous.cleartravel.feature.menu

import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.itsluminous.cleartravel.core.data.backup.ImportPreview
import com.itsluminous.cleartravel.core.designsystem.DateFormats
import com.itsluminous.cleartravel.core.designsystem.component.ClearTravelCard
import com.itsluminous.cleartravel.core.designsystem.component.ExplainableIcon
import com.itsluminous.cleartravel.core.designsystem.component.InputDialogProperties
import com.itsluminous.cleartravel.core.designsystem.component.PasswordField
import com.itsluminous.cleartravel.core.designsystem.component.PasswordFieldRole
import com.itsluminous.cleartravel.core.google.backup.DriveBackupInfo
import com.itsluminous.cleartravel.core.model.BackupSchedule

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
                BackupRestoreEvent.DriveDownloadFailed ->
                    snackbarHostState.showSnackbar(context.getString(R.string.menu_backup_drive_download_failed))
                BackupRestoreEvent.WrongBackupPassword ->
                    snackbarHostState.showSnackbar(context.getString(R.string.menu_backup_error_wrong_password))
                BackupRestoreEvent.VaultLocked ->
                    snackbarHostState.showSnackbar(context.getString(R.string.menu_backup_error_locked))
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

            // ADR-037: automatic backup cadence — local always, Drive follows when enabled.
            ClearTravelCard {
                Text(
                    text = stringResource(R.string.menu_backup_schedule_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.menu_backup_schedule_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                )
                for (schedule in BackupSchedule.entries) {
                    RadioOptionRow(
                        labelRes = schedule.labelRes(),
                        selected = uiState.schedule == schedule,
                        onSelect = { viewModel.setBackupSchedule(schedule) },
                    )
                }
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
                                DateFormats.formatTimestamp(lastBackup.createdAt),
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

            ClearTravelCard {
                Text(
                    text = stringResource(R.string.menu_backup_drive_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                if (!uiState.driveLinked) {
                    Text(
                        text = stringResource(R.string.menu_backup_drive_not_linked),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                } else {
                    val newest = uiState.driveBackups.firstOrNull()
                    Text(
                        text =
                            if (newest == null) {
                                stringResource(R.string.menu_backup_drive_none)
                            } else {
                                stringResource(
                                    R.string.menu_backup_drive_last,
                                    DateFormats.formatTimestamp(newest.createdAt),
                                    Formatter.formatShortFileSize(context, newest.sizeBytes),
                                )
                            },
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Button(
                        onClick = viewModel::openDriveList,
                        enabled = !uiState.inProgress,
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        Text(stringResource(R.string.menu_backup_drive_restore_action))
                    }
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

    // ADR-031: the backup was sealed under another password (other device / fresh
    // install / changed password) — ask for the password it was created with.
    if (uiState.passwordPrompt != null) {
        BackupPasswordDialog(
            inProgress = uiState.inProgress,
            onSubmit = viewModel::submitSourcePassword,
            onDismiss = viewModel::dismissPasswordPrompt,
        )
    }

    if (uiState.showDriveList) {
        DriveBackupListDialog(
            backups = uiState.driveBackups,
            onPick = viewModel::restoreFromDrive,
            onDismiss = viewModel::dismissDriveList,
        )
    }

    uiState.freshRestorePrompt?.let { backup ->
        AlertDialog(
            onDismissRequest = viewModel::dismissFreshRestore,
            title = { Text(stringResource(R.string.menu_backup_fresh_restore_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.menu_backup_fresh_restore_message,
                        DateFormats.formatTimestamp(backup.createdAt),
                        Formatter.formatShortFileSize(context, backup.sizeBytes),
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmFreshRestore) {
                    Text(stringResource(R.string.menu_backup_fresh_restore_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissFreshRestore) {
                    Text(stringResource(R.string.menu_backup_fresh_restore_decline))
                }
            },
        )
    }
}

private fun BackupSchedule.labelRes(): Int =
    when (this) {
        BackupSchedule.OFF -> R.string.menu_backup_schedule_off
        BackupSchedule.DAILY -> R.string.menu_backup_schedule_daily
        BackupSchedule.WEEKLY -> R.string.menu_backup_schedule_weekly
        BackupSchedule.MONTHLY -> R.string.menu_backup_schedule_monthly
    }

/** Picker over the ClearTravel backups found in Drive (newest first). */
@Composable
private fun DriveBackupListDialog(
    backups: List<DriveBackupInfo>,
    onPick: (DriveBackupInfo) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.menu_backup_drive_list_title)) },
        text = {
            if (backups.isEmpty()) {
                Text(stringResource(R.string.menu_backup_drive_list_empty))
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    backups.forEach { backup ->
                        Column(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onPick(backup) }
                                    .padding(vertical = 8.dp),
                        ) {
                            Text(text = backup.fileName, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text =
                                    stringResource(
                                        R.string.menu_backup_drive_last,
                                        DateFormats.formatTimestamp(backup.createdAt),
                                        Formatter.formatShortFileSize(context, backup.sizeBytes),
                                    ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.menu_cancel)) }
        },
    )
}

/** Source-password prompt for a backup from another password/device (ADR-031). */
@Composable
private fun BackupPasswordDialog(
    inProgress: Boolean,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var password by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = InputDialogProperties,
        title = { Text(stringResource(R.string.menu_backup_password_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.menu_backup_password_message))
                PasswordField(
                    value = password,
                    onValueChange = { password = it },
                    label = stringResource(R.string.menu_backup_password_label),
                    role = PasswordFieldRole.EXISTING,
                    enabled = !inProgress,
                    onImeAction = { if (password.isNotEmpty()) onSubmit(password) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(password) }, enabled = password.isNotEmpty() && !inProgress) {
                Text(stringResource(R.string.menu_backup_password_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.menu_cancel)) }
        },
    )
}

/** Confirmation before merging: backup date + record counts (spec feature 6). */
@Composable
private fun ImportConfirmDialog(
    preview: ImportPreview,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val summary = ImportPreviewSummary.of(preview)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.menu_backup_import_confirm_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(
                        R.string.menu_backup_import_confirm_message,
                        DateFormats.formatTimestamp(preview.createdAt),
                        preview.totalRows,
                    ),
                )
                Text(
                    stringResource(
                        R.string.menu_backup_import_confirm_counts,
                        summary.trips,
                        summary.journeys,
                        summary.checklists,
                        summary.documents,
                        summary.attachments,
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
