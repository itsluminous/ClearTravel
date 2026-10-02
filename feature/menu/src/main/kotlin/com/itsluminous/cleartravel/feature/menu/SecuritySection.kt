package com.itsluminous.cleartravel.feature.menu

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.itsluminous.cleartravel.core.data.sync.SyncWorkKind
import com.itsluminous.cleartravel.core.data.sync.SyncWorkStatus
import com.itsluminous.cleartravel.core.designsystem.DateFormats
import com.itsluminous.cleartravel.core.designsystem.component.AutoShrinkText
import com.itsluminous.cleartravel.core.designsystem.component.ClearTravelCard
import com.itsluminous.cleartravel.core.designsystem.component.InputDialogProperties
import com.itsluminous.cleartravel.core.designsystem.component.PasswordField
import com.itsluminous.cleartravel.core.designsystem.component.PasswordFieldRole
import com.itsluminous.cleartravel.core.security.biometric.BiometricUnlock
import com.itsluminous.cleartravel.core.security.lock.LockTiming

/**
 * Settings → Security (ADR-031): change password, biometric unlock toggle, lock
 * timing. Biometric enrolment runs a `BiometricPrompt` around the Keystore ENCRYPT
 * cipher so the DEK wrap happens only inside a successful authentication.
 *
 * ADR-043: the "Allow sync while locked" switch (ON → password confirmation dialog →
 * re-wrap; OFF → wrap + Keystore key deleted) and the *Background sync* status rows.
 */
@Composable
internal fun SecuritySection(
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
    viewModel: SecuritySettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val biometricsAvailable = BiometricUnlock.isAvailable(context)
    val promptTitle = stringResource(R.string.menu_security_biometric_prompt_title)
    val promptNegative = stringResource(R.string.menu_cancel)

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            val message =
                when (event) {
                    SecurityEvent.PasswordChanged -> R.string.menu_security_password_changed
                    SecurityEvent.CurrentPasswordWrong -> R.string.menu_security_current_wrong
                    SecurityEvent.BiometricEnabled -> R.string.menu_security_biometric_enabled
                    SecurityEvent.BiometricDisabled -> R.string.menu_security_biometric_disabled
                    SecurityEvent.BiometricSetupFailed -> R.string.menu_security_biometric_failed
                    SecurityEvent.BackgroundSyncEnabled -> R.string.menu_security_background_sync_enabled
                    SecurityEvent.BackgroundSyncDisabled -> R.string.menu_security_background_sync_disabled
                    SecurityEvent.BackgroundSyncPasswordWrong -> R.string.menu_security_background_sync_wrong_password
                    SecurityEvent.BackgroundSyncSetupFailed -> R.string.menu_security_background_sync_failed
                }
            snackbarHostState.showSnackbar(context.getString(message))
        }
    }

    fun startBiometricEnrolment() {
        val cipher = viewModel.biometricEnrolCipher() ?: return
        val shown =
            BiometricUnlock.prompt(
                context = context,
                cipher = cipher,
                title = promptTitle,
                negativeButton = promptNegative,
                onAuthenticated = viewModel::enableBiometric,
                onDismissed = viewModel::biometricPromptFailed,
                onError = { viewModel.biometricPromptFailed() },
            )
        if (!shown) viewModel.biometricPromptFailed()
    }

    Column(modifier = modifier.padding(top = 24.dp)) {
        Text(
            text = stringResource(R.string.menu_security_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        ClearTravelCard {
            Text(stringResource(R.string.menu_security_description), style = MaterialTheme.typography.bodyLarge)
            OutlinedButton(
                onClick = viewModel::openChangePassword,
                enabled = state.hasPassword && !state.busy,
                modifier = Modifier.padding(top = 12.dp),
            ) {
                AutoShrinkText(stringResource(R.string.menu_security_change_password))
            }

            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.menu_security_biometric_title), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text =
                            stringResource(
                                when {
                                    !state.hasPassword -> R.string.menu_security_biometric_needs_password
                                    !biometricsAvailable -> R.string.menu_security_biometric_unavailable
                                    else -> R.string.menu_security_biometric_subtitle
                                },
                            ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state.biometricEnabled,
                    enabled = state.hasPassword && biometricsAvailable && !state.busy,
                    onCheckedChange = { wanted -> if (wanted) startBiometricEnrolment() else viewModel.disableBiometric() },
                )
            }

            Text(
                text = stringResource(R.string.menu_security_lock_timing_title),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
            )
            for (timing in LockTiming.entries) {
                RadioOptionRow(
                    labelRes = timing.labelRes(),
                    selected = state.lockTiming == timing,
                    onSelect = { viewModel.setLockTiming(timing) },
                )
            }

            // ADR-043: opt-in background key.
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.menu_security_background_sync_switch_title), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = stringResource(R.string.menu_security_background_sync_switch_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state.backgroundSyncEnabled,
                    enabled = state.hasPassword && !state.busy,
                    onCheckedChange = { wanted ->
                        if (wanted) viewModel.requestEnableBackgroundSync() else viewModel.disableBackgroundSync()
                    },
                    modifier = Modifier.testTag(BACKGROUND_SYNC_SWITCH_TAG),
                )
            }

            BackgroundSyncStatus(statuses = state.syncStatuses, modifier = Modifier.padding(top = 16.dp))
        }
    }

    if (state.confirmingBackgroundSync) {
        ConfirmBackgroundSyncDialog(
            busy = state.busy,
            onSubmit = viewModel::enableBackgroundSync,
            onDismiss = viewModel::dismissBackgroundSyncConfirmation,
        )
    }

    if (state.changingPassword) {
        ChangePasswordDialog(
            error = state.changeError,
            busy = state.busy,
            onEdit = viewModel::clearChangeError,
            onSubmit = viewModel::changePassword,
            onDismiss = viewModel::dismissChangePassword,
        )
    }
}

/** Test tag of the ADR-043 "Allow sync while locked" switch (the e2e suite flips it). */
const val BACKGROUND_SYNC_SWITCH_TAG = "menu_security_background_sync_switch"

/**
 * ADR-043: per-job "last successful / skipped while locked" lines. One-liners are
 * [AutoShrinkText] (a timestamp that wrapped would read like two rows).
 */
@Composable
private fun BackgroundSyncStatus(
    statuses: List<SyncWorkStatus>,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(stringResource(R.string.menu_security_sync_status_title), style = MaterialTheme.typography.bodyLarge)
        Text(
            text = stringResource(R.string.menu_security_sync_status_explanation),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        for (status in statuses) {
            Spacer(Modifier.height(8.dp))
            AutoShrinkText(
                text = stringResource(status.kind.labelRes()),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth(),
            )
            AutoShrinkText(
                text =
                    status.lastCompletedAt?.let {
                        stringResource(
                            R.string.menu_security_sync_last_success,
                            DateFormats.formatTimestamp(it),
                        )
                    }
                        ?: stringResource(R.string.menu_security_sync_last_success_never),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                minScale = 0.8f,
                modifier = Modifier.fillMaxWidth(),
            )
            AutoShrinkText(
                text =
                    status.lastDeferredAt?.let {
                        stringResource(
                            R.string.menu_security_sync_skipped,
                            DateFormats.formatTimestamp(it),
                            pluralStringResource(
                                R.plurals.menu_security_sync_skipped_count,
                                status.deferredSinceUnlock,
                                status.deferredSinceUnlock,
                            ),
                        )
                    } ?: stringResource(R.string.menu_security_sync_skipped_never),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                minScale = 0.8f,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@StringRes
private fun SyncWorkKind.labelRes(): Int =
    when (this) {
        SyncWorkKind.FLIGHT_POLL -> R.string.menu_security_sync_kind_flights
        SyncWorkKind.CALENDAR_SYNC -> R.string.menu_security_sync_kind_calendar
        SyncWorkKind.DRIVE_UPLOAD -> R.string.menu_security_sync_kind_drive
        SyncWorkKind.SCHEDULED_BACKUP -> R.string.menu_security_sync_kind_backup
        SyncWorkKind.TRAIN_REMINDER -> R.string.menu_security_sync_kind_train_reminder
    }

/** ADR-043: the fresh password confirmation before the DEK is re-wrapped under the background key. */
@Composable
private fun ConfirmBackgroundSyncDialog(
    busy: Boolean,
    onSubmit: (password: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var password by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = InputDialogProperties,
        title = { Text(stringResource(R.string.menu_security_background_sync_confirm_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.menu_security_background_sync_confirm_body))
                PasswordField(
                    value = password,
                    onValueChange = { password = it },
                    label = stringResource(R.string.menu_security_current_password),
                    role = PasswordFieldRole.EXISTING,
                    enabled = !busy,
                    onImeAction = { onSubmit(password) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(password) }, enabled = !busy && password.isNotEmpty()) {
                AutoShrinkText(stringResource(R.string.menu_security_background_sync_confirm_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { AutoShrinkText(stringResource(R.string.menu_cancel)) }
        },
    )
}

/** One radio option of an exclusive-choice list (lock timing, backup schedule). */
@Composable
internal fun RadioOptionRow(
    @StringRes labelRes: Int,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .semantics { role = Role.RadioButton }
                .clickable(onClick = onSelect)
                .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(text = stringResource(labelRes), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun ChangePasswordDialog(
    error: ChangePasswordError?,
    busy: Boolean,
    onEdit: () -> Unit,
    onSubmit: (current: String, new: String, confirm: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var current by rememberSaveable { mutableStateOf("") }
    var new by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = InputDialogProperties,
        title = { Text(stringResource(R.string.menu_security_change_password)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.menu_security_change_password_body))
                PasswordField(
                    value = current,
                    onValueChange = {
                        current = it
                        onEdit()
                    },
                    label = stringResource(R.string.menu_security_current_password),
                    role = PasswordFieldRole.EXISTING,
                    enabled = !busy,
                    imeAction = ImeAction.Next,
                    modifier = Modifier.fillMaxWidth(),
                )
                PasswordField(
                    value = new,
                    onValueChange = {
                        new = it
                        onEdit()
                    },
                    label = stringResource(R.string.menu_security_new_password),
                    role = PasswordFieldRole.NEW,
                    isError = error == ChangePasswordError.TOO_SHORT,
                    supportingText =
                        if (error == ChangePasswordError.TOO_SHORT) {
                            stringResource(R.string.menu_security_error_too_short, SecuritySettingsViewModel.MIN_PASSWORD_LENGTH)
                        } else {
                            null
                        },
                    enabled = !busy,
                    imeAction = ImeAction.Next,
                    modifier = Modifier.fillMaxWidth(),
                )
                PasswordField(
                    value = confirm,
                    onValueChange = {
                        confirm = it
                        onEdit()
                    },
                    label = stringResource(R.string.menu_security_confirm_password),
                    role = PasswordFieldRole.NEW,
                    isError = error == ChangePasswordError.MISMATCH,
                    supportingText =
                        if (error ==
                            ChangePasswordError.MISMATCH
                        ) {
                            stringResource(R.string.menu_security_error_mismatch)
                        } else {
                            null
                        },
                    enabled = !busy,
                    onImeAction = { onSubmit(current, new, confirm) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSubmit(current, new, confirm) },
                enabled = !busy && current.isNotEmpty() && new.isNotEmpty() && confirm.isNotEmpty(),
            ) {
                AutoShrinkText(stringResource(R.string.menu_security_change_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { AutoShrinkText(stringResource(R.string.menu_cancel)) }
        },
    )
}

@StringRes
private fun LockTiming.labelRes(): Int =
    when (this) {
        LockTiming.IMMEDIATELY -> R.string.menu_security_lock_immediately
        LockTiming.ONE_MINUTE -> R.string.menu_security_lock_1m
        LockTiming.FIVE_MINUTES -> R.string.menu_security_lock_5m
        LockTiming.FIFTEEN_MINUTES -> R.string.menu_security_lock_15m
        LockTiming.NEVER -> R.string.menu_security_lock_never
    }
