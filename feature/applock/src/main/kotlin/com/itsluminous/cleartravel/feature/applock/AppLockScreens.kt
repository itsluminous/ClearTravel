package com.itsluminous.cleartravel.feature.applock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.itsluminous.cleartravel.core.designsystem.component.ClearTravelCard
import com.itsluminous.cleartravel.core.designsystem.component.PasswordField
import com.itsluminous.cleartravel.core.designsystem.component.PasswordFieldRole
import com.itsluminous.cleartravel.core.security.biometric.BiometricUnlock

/**
 * The app-lock gate (ADR-031): wraps the whole shell. Shows the blocking first-run
 * password setup, the unlock screen (password or BiometricPrompt), a "securing your
 * data" step while the database opens / migrates, and only then [content].
 * [onUnlocked] fires once per gate opening (the shell runs its startup housekeeping
 * there — nothing may touch the database before).
 */
@Composable
fun AppLockGate(
    onUnlocked: suspend () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AppLockViewModel = hiltViewModel(),
    content: @Composable () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val feedback by viewModel.feedback.collectAsStateWithLifecycle()
    LaunchedEffect(state) {
        if (state is AppLockUiState.Ready) onUnlocked()
    }
    when (val current = state) {
        AppLockUiState.Setup ->
            SetupPasswordScreen(
                feedback = feedback,
                onSubmit = viewModel::setUp,
                onEdit = viewModel::clearSetupError,
                modifier = modifier,
            )
        is AppLockUiState.Locked ->
            UnlockScreen(
                biometricEnabled = current.biometricEnabled,
                feedback = feedback,
                onUnlock = viewModel::unlock,
                onEdit = viewModel::clearSetupError,
                biometricCipher = viewModel::biometricUnlockCipher,
                onBiometricAuthenticated = viewModel::unlockWithBiometric,
                onRetryPreparation = viewModel::retryPreparation,
                modifier = modifier,
            )
        AppLockUiState.Preparing -> PreparingScreen(modifier)
        AppLockUiState.Ready -> content()
    }
}

/** First run: create the password. Blocking — there is no way past it. */
@Composable
internal fun SetupPasswordScreen(
    feedback: AppLockFeedback,
    onSubmit: (password: String, confirm: String) -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var password by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }
    val strength = PasswordRules.strength(password)

    LockScaffold(modifier) {
        Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Text(stringResource(R.string.applock_setup_title), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.applock_setup_body), style = MaterialTheme.typography.bodyLarge)
        PasswordField(
            value = password,
            onValueChange = {
                password = it
                onEdit()
            },
            label = stringResource(R.string.applock_setup_password_label),
            role = PasswordFieldRole.NEW,
            isError = feedback.setupError == SetupError.TOO_SHORT,
            supportingText =
                when {
                    feedback.setupError == SetupError.TOO_SHORT ->
                        stringResource(
                            R.string.applock_error_too_short,
                            PasswordRules.MIN_LENGTH,
                        )
                    password.isEmpty() -> stringResource(R.string.applock_setup_min_length, PasswordRules.MIN_LENGTH)
                    else -> stringResource(strength.labelRes())
                },
            enabled = !feedback.busy,
            imeAction = ImeAction.Next,
            modifier = Modifier.fillMaxWidth(),
        )
        PasswordField(
            value = confirm,
            onValueChange = {
                confirm = it
                onEdit()
            },
            label = stringResource(R.string.applock_setup_confirm_label),
            role = PasswordFieldRole.NEW,
            isError = feedback.setupError == SetupError.MISMATCH,
            supportingText = if (feedback.setupError == SetupError.MISMATCH) stringResource(R.string.applock_error_mismatch) else null,
            enabled = !feedback.busy,
            onImeAction = { onSubmit(password, confirm) },
            modifier = Modifier.fillMaxWidth(),
        )
        ClearTravelCard(modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.applock_setup_warning_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.applock_setup_warning_body), style = MaterialTheme.typography.bodyLarge)
        }
        Button(
            onClick = { onSubmit(password, confirm) },
            enabled = !feedback.busy && password.isNotEmpty() && confirm.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.applock_setup_action))
        }
        if (feedback.preparationFailed) PreparationFailedText()
    }
}

/** Cold start / re-lock: password, or BiometricPrompt when enabled. */
@Composable
internal fun UnlockScreen(
    biometricEnabled: Boolean,
    feedback: AppLockFeedback,
    onUnlock: (String) -> Unit,
    onEdit: () -> Unit,
    biometricCipher: () -> javax.crypto.Cipher?,
    onBiometricAuthenticated: (javax.crypto.Cipher) -> Unit,
    onRetryPreparation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var password by rememberSaveable { mutableStateOf("") }
    var biometricError by rememberSaveable { mutableStateOf<String?>(null) }
    val promptTitle = stringResource(R.string.applock_biometric_prompt_title)
    val promptNegative = stringResource(R.string.applock_biometric_use_password)
    val canPrompt = biometricEnabled && !feedback.biometricUnavailable && BiometricUnlock.isAvailable(context)

    fun startBiometric() {
        val cipher = biometricCipher() ?: return
        BiometricUnlock.prompt(
            context = context,
            cipher = cipher,
            title = promptTitle,
            negativeButton = promptNegative,
            onAuthenticated = onBiometricAuthenticated,
            onDismissed = {},
            onError = { biometricError = it },
        )
    }

    // Offer biometrics straight away on a cold start; the password stays one tap away.
    LaunchedEffect(canPrompt) {
        if (canPrompt) startBiometric()
    }

    LockScaffold(modifier) {
        Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Text(stringResource(R.string.applock_unlock_title), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.applock_unlock_body), style = MaterialTheme.typography.bodyLarge)
        PasswordField(
            value = password,
            onValueChange = {
                password = it
                onEdit()
            },
            label = stringResource(R.string.applock_unlock_password_label),
            role = PasswordFieldRole.EXISTING,
            isError = feedback.wrongPassword,
            supportingText = if (feedback.wrongPassword) stringResource(R.string.applock_error_wrong_password) else null,
            enabled = !feedback.busy,
            onImeAction = { onUnlock(password) },
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { onUnlock(password) },
            enabled = !feedback.busy && password.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.applock_unlock_action))
        }
        if (canPrompt) {
            OutlinedButton(onClick = ::startBiometric, enabled = !feedback.busy, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Fingerprint, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.applock_biometric_action))
            }
        }
        if (feedback.biometricUnavailable) {
            Text(stringResource(R.string.applock_biometric_unavailable), style = MaterialTheme.typography.bodyMedium)
        }
        biometricError?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        if (feedback.preparationFailed) {
            PreparationFailedText()
            OutlinedButton(onClick = onRetryPreparation, enabled = !feedback.busy) {
                Text(stringResource(R.string.applock_preparation_retry))
            }
        }
        if (feedback.busy) CircularProgressIndicator()
    }
}

@Composable
private fun PreparingScreen(modifier: Modifier = Modifier) {
    LockScaffold(modifier) {
        CircularProgressIndicator()
        Text(stringResource(R.string.applock_preparing_title), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.applock_preparing_body), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun PreparationFailedText() {
    Text(
        stringResource(R.string.applock_preparation_failed),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.error,
    )
}

@Composable
private fun LockScaffold(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(24.dp))
            content()
        }
    }
}

private fun PasswordStrength.labelRes(): Int =
    when (this) {
        PasswordStrength.TOO_SHORT -> R.string.applock_strength_too_short
        PasswordStrength.WEAK -> R.string.applock_strength_weak
        PasswordStrength.FAIR -> R.string.applock_strength_fair
        PasswordStrength.STRONG -> R.string.applock_strength_strong
    }
