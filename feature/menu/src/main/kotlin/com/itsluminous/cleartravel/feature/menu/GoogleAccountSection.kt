package com.itsluminous.cleartravel.feature.menu

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.itsluminous.cleartravel.core.designsystem.component.ClearTravelCard
import com.itsluminous.cleartravel.core.google.auth.GoogleFeature
import com.itsluminous.cleartravel.core.google.auth.GoogleLinkState

/**
 * Settings "Google account" section (spec feature 5): link/unlink with the linked
 * account shown, plus the Calendar sync / Drive uploads / Drive backup toggles. With
 * no configured client id everything renders disabled with an explanation — never a
 * crash (docs/google-setup.md).
 */
@Composable
internal fun GoogleAccountSection(
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
    viewModel: GoogleSettingsViewModel = hiltViewModel(),
) {
    val linkState by viewModel.linkState.collectAsStateWithLifecycle()
    val settings by viewModel.featureSettings.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val consentLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            viewModel.onConsentResult(result.data)
        }
    uiState.consentIntent?.let { pendingIntent ->
        LaunchedEffect(pendingIntent) {
            viewModel.consentLaunched()
            consentLauncher.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is GoogleSettingsEvent.Linked ->
                    snackbarHostState.showSnackbar(context.getString(R.string.menu_google_linked_snackbar, event.email))
                GoogleSettingsEvent.Disconnected ->
                    snackbarHostState.showSnackbar(context.getString(R.string.menu_google_disconnected))
                GoogleSettingsEvent.LinkFailed ->
                    snackbarHostState.showSnackbar(context.getString(R.string.menu_google_link_failed))
                GoogleSettingsEvent.ScopeDenied ->
                    snackbarHostState.showSnackbar(context.getString(R.string.menu_google_scope_denied))
            }
        }
    }

    Column(modifier = modifier) {
        Text(
            text = stringResource(R.string.menu_google_section),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
        )
        ClearTravelCard {
            when (val state = linkState) {
                GoogleLinkState.NotConfigured -> {
                    Text(
                        text = stringResource(R.string.menu_google_not_configured),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                GoogleLinkState.NotLinked -> {
                    Text(
                        text = stringResource(R.string.menu_google_not_linked),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = { viewModel.link(context) },
                        enabled = !uiState.inProgress,
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        Text(stringResource(R.string.menu_google_link_action))
                    }
                }
                is GoogleLinkState.Linked -> {
                    Text(
                        text = stringResource(R.string.menu_google_linked_as, state.email),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    OutlinedButton(
                        onClick = viewModel::requestDisconnect,
                        enabled = !uiState.inProgress,
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        Text(stringResource(R.string.menu_google_disconnect_action))
                    }
                }
            }
        }

        val linked = linkState is GoogleLinkState.Linked
        GoogleToggleRow(
            titleRes = R.string.menu_google_calendar_sync,
            subtitleRes = R.string.menu_google_calendar_sync_subtitle,
            checked = settings.calendarSyncEnabled,
            enabled = linked && !uiState.inProgress,
            onToggle = { viewModel.setFeatureEnabled(GoogleFeature.CALENDAR_SYNC, it, context) },
        )
        GoogleToggleRow(
            titleRes = R.string.menu_google_drive_uploads,
            subtitleRes = R.string.menu_google_drive_uploads_subtitle,
            checked = settings.driveUploadsEnabled,
            enabled = linked && !uiState.inProgress,
            onToggle = { viewModel.setFeatureEnabled(GoogleFeature.DRIVE_UPLOADS, it, context) },
        )
        GoogleToggleRow(
            titleRes = R.string.menu_google_drive_backup,
            subtitleRes = R.string.menu_google_drive_backup_subtitle,
            checked = settings.driveBackupEnabled,
            enabled = linked && !uiState.inProgress,
            onToggle = { viewModel.setFeatureEnabled(GoogleFeature.DRIVE_BACKUP, it, context) },
        )
    }

    if (uiState.showDisconnectDialog) {
        AlertDialog(
            onDismissRequest = viewModel::dismissDisconnect,
            title = { Text(stringResource(R.string.menu_google_disconnect_title)) },
            text = { Text(stringResource(R.string.menu_google_disconnect_message)) },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmDisconnect(deleteCalendar = false) }) {
                    Text(stringResource(R.string.menu_google_disconnect_keep))
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { viewModel.confirmDisconnect(deleteCalendar = true) }) {
                        Text(stringResource(R.string.menu_google_disconnect_delete))
                    }
                    TextButton(onClick = viewModel::dismissDisconnect) {
                        Text(stringResource(R.string.menu_cancel))
                    }
                }
            },
        )
    }
}

@Composable
private fun GoogleToggleRow(
    @StringRes titleRes: Int,
    @StringRes subtitleRes: Int,
    checked: Boolean,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = stringResource(titleRes), style = MaterialTheme.typography.bodyLarge)
            Text(
                text = stringResource(subtitleRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onToggle, enabled = enabled)
    }
}
