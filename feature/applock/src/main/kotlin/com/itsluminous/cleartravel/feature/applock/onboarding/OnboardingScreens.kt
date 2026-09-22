package com.itsluminous.cleartravel.feature.applock.onboarding

import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.itsluminous.cleartravel.core.designsystem.component.ClearTravelCard
import com.itsluminous.cleartravel.core.designsystem.component.PasswordField
import com.itsluminous.cleartravel.core.designsystem.component.PasswordFieldRole
import com.itsluminous.cleartravel.core.google.auth.GoogleLinkState
import com.itsluminous.cleartravel.core.google.backup.DriveBackupInfo
import com.itsluminous.cleartravel.feature.applock.LockScaffold
import com.itsluminous.cleartravel.feature.applock.R
import com.itsluminous.cleartravel.feature.applock.StepIndicator
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Accept any file on import — SAF providers label backup files inconsistently. */
private val IMPORT_MIME_TYPES = arrayOf("application/zip", "application/octet-stream", "*/*")

/**
 * Wizard steps 2–4 (ADR-032), hosted by the lock gate once the password exists and
 * storage is open: Google account (or offline) → restore a backup (file / Drive) or
 * start fresh → the backup's own password when needed. Finishing clears the
 * onboarding flag and the gate reveals the app.
 */
@Composable
fun OnboardingWizard(
    modifier: Modifier = Modifier,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    when (state.step) {
        OnboardingStep.GOOGLE ->
            GoogleStep(
                state = state,
                onConnect = viewModel::connectGoogle,
                onConsentLaunched = viewModel::consentLaunched,
                onConsentResult = viewModel::onConsentResult,
                onContinue = viewModel::continueToRestore,
                modifier = modifier,
            )
        OnboardingStep.RESTORE ->
            RestoreStep(
                state = state,
                onRestoreFile = viewModel::restoreFromFile,
                onRestoreDrive = viewModel::restoreFromDrive,
                onRetryDrive = viewModel::checkDrive,
                onStartFresh = viewModel::startFresh,
                modifier = modifier,
            )
        OnboardingStep.BACKUP_PASSWORD ->
            BackupPasswordStep(
                state = state,
                onSubmit = viewModel::submitBackupPassword,
                onEdit = viewModel::clearBackupPasswordError,
                onCancel = viewModel::cancelRestore,
                modifier = modifier,
            )
    }
}

/** Step 2: connect a Google account for Drive backups, or use the app offline. */
@Composable
internal fun GoogleStep(
    state: OnboardingUiState,
    onConnect: (android.content.Context) -> Unit,
    onConsentLaunched: () -> Unit,
    onConsentResult: (android.content.Intent?) -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val consentLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            onConsentResult(result.data)
        }
    state.consentIntent?.let { pendingIntent ->
        LaunchedEffect(pendingIntent) {
            onConsentLaunched()
            consentLauncher.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
        }
    }

    LockScaffold(modifier) {
        StepIndicator(step = 2)
        Icon(Icons.Filled.CloudUpload, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Text(stringResource(R.string.applock_onboarding_google_title), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.applock_onboarding_google_body), style = MaterialTheme.typography.bodyLarge)
        when (val link = state.linkState) {
            is GoogleLinkState.Linked -> {
                Text(
                    stringResource(R.string.applock_onboarding_google_linked_as, link.email),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Button(onClick = onContinue, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.applock_onboarding_continue))
                }
            }
            GoogleLinkState.NotConfigured, GoogleLinkState.NotLinked -> {
                Button(
                    onClick = { onConnect(context) },
                    enabled = state.googleConfigured && !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.applock_onboarding_google_connect))
                }
                if (!state.googleConfigured) {
                    Text(
                        stringResource(R.string.applock_onboarding_google_not_configured),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(onClick = onContinue, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.applock_onboarding_google_offline))
                }
            }
        }
        state.error?.let { ErrorLine(it.labelRes()) }
        if (state.busy) CircularProgressIndicator()
    }
}

/** Step 3: restore a backup from a file or Drive, or start fresh. */
@Composable
internal fun RestoreStep(
    state: OnboardingUiState,
    onRestoreFile: (android.net.Uri) -> Unit,
    onRestoreDrive: (DriveBackupInfo) -> Unit,
    onRetryDrive: () -> Unit,
    onStartFresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val filePicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) onRestoreFile(uri)
        }

    LockScaffold(modifier) {
        StepIndicator(step = 3)
        Icon(Icons.Filled.Restore, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Text(stringResource(R.string.applock_onboarding_restore_title), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.applock_onboarding_restore_body), style = MaterialTheme.typography.bodyLarge)

        ClearTravelCard(modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.applock_onboarding_restore_file_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.applock_onboarding_restore_file_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            OutlinedButton(
                onClick = { filePicker.launch(IMPORT_MIME_TYPES) },
                enabled = !state.busy,
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Text(stringResource(R.string.applock_onboarding_restore_file_action))
            }
        }

        if (state.drive !is DriveCheck.NotLinked) {
            ClearTravelCard(modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.applock_onboarding_restore_drive_title), style = MaterialTheme.typography.titleMedium)
                when (val drive = state.drive) {
                    DriveCheck.NotLinked -> Unit
                    DriveCheck.NoAccess ->
                        Text(
                            stringResource(R.string.applock_onboarding_restore_drive_no_access),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    DriveCheck.Checking -> {
                        Text(
                            stringResource(R.string.applock_onboarding_restore_drive_checking),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        CircularProgressIndicator(modifier = Modifier.padding(top = 8.dp))
                    }
                    DriveCheck.None -> {
                        Text(
                            stringResource(R.string.applock_onboarding_restore_drive_none),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        TextButton(onClick = onRetryDrive, enabled = !state.busy) {
                            Text(stringResource(R.string.applock_onboarding_restore_drive_recheck))
                        }
                    }
                    is DriveCheck.Found -> {
                        Text(
                            stringResource(R.string.applock_onboarding_restore_drive_found, drive.backups.size),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        drive.backups.take(MAX_DRIVE_ROWS).forEach { backup ->
                            Column(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable(enabled = !state.busy) { onRestoreDrive(backup) }
                                        .padding(vertical = 8.dp),
                            ) {
                                Text(backup.fileName, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    stringResource(
                                        R.string.applock_onboarding_restore_drive_row,
                                        formatInstant(backup.createdAt),
                                        Formatter.formatShortFileSize(context, backup.sizeBytes),
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }

        state.error?.let { ErrorLine(it.labelRes()) }
        if (state.busy) CircularProgressIndicator()
        Spacer(Modifier.size(8.dp))
        Button(onClick = onStartFresh, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.applock_onboarding_start_fresh))
        }
    }
}

/**
 * Step 4: the backup was sealed with its creator's password — which may differ from
 * the one just created. Wrong password → inline error, retry.
 */
@Composable
internal fun BackupPasswordStep(
    state: OnboardingUiState,
    onSubmit: (String) -> Unit,
    onEdit: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var password by rememberSaveable { mutableStateOf("") }
    val sourceName =
        when (val source = state.restoreSource) {
            is RestoreSource.Drive -> source.backup.fileName
            is RestoreSource.LocalFile -> source.displayName
            null -> ""
        }

    LockScaffold(modifier) {
        StepIndicator(step = 4)
        Icon(Icons.Filled.Key, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Text(stringResource(R.string.applock_onboarding_backup_password_title), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.applock_onboarding_backup_password_body), style = MaterialTheme.typography.bodyLarge)
        ClearTravelCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                stringResource(R.string.applock_onboarding_backup_password_may_differ),
                style = MaterialTheme.typography.bodyLarge,
            )
            if (sourceName.isNotBlank()) {
                Text(
                    stringResource(R.string.applock_onboarding_backup_password_file, sourceName),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        PasswordField(
            value = password,
            onValueChange = {
                password = it
                onEdit()
            },
            label = stringResource(R.string.applock_onboarding_backup_password_label),
            role = PasswordFieldRole.EXISTING,
            isError = state.wrongBackupPassword,
            supportingText = if (state.wrongBackupPassword) stringResource(R.string.applock_onboarding_backup_password_wrong) else null,
            enabled = !state.busy,
            onImeAction = { if (password.isNotEmpty()) onSubmit(password) },
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { onSubmit(password) },
            enabled = !state.busy && password.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.applock_onboarding_backup_password_action))
        }
        OutlinedButton(onClick = onCancel, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.applock_onboarding_backup_password_cancel))
        }
        if (state.busy) CircularProgressIndicator()
    }
}

@Composable
private fun ErrorLine(
    @StringRes labelRes: Int,
) {
    Text(
        stringResource(labelRes),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.error,
    )
}

@StringRes
private fun OnboardingError.labelRes(): Int =
    when (this) {
        OnboardingError.GOOGLE_LINK_FAILED -> R.string.applock_onboarding_error_google
        OnboardingError.BACKUP_VERSION_TOO_NEW -> R.string.applock_onboarding_error_backup_version
        OnboardingError.BACKUP_UNREADABLE -> R.string.applock_onboarding_error_backup_unreadable
        OnboardingError.BACKUP_IO -> R.string.applock_onboarding_error_backup_io
    }

private fun formatInstant(instant: Instant): String =
    DateTimeFormatter
        .ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withZone(ZoneId.systemDefault())
        .format(instant)

private const val MAX_DRIVE_ROWS = 5
