package com.itsluminous.cleartravel.feature.applock.onboarding

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsluminous.cleartravel.core.data.backup.BackupException
import com.itsluminous.cleartravel.core.data.backup.BackupManager
import com.itsluminous.cleartravel.core.data.backup.MergeSummary
import com.itsluminous.cleartravel.core.data.repository.SettingsRepository
import com.itsluminous.cleartravel.core.google.auth.GoogleAccountManager
import com.itsluminous.cleartravel.core.google.auth.GoogleFeature
import com.itsluminous.cleartravel.core.google.auth.GoogleLinkException
import com.itsluminous.cleartravel.core.google.auth.GoogleLinkState
import com.itsluminous.cleartravel.core.google.backup.DriveBackupInfo
import com.itsluminous.cleartravel.core.google.backup.DriveBackupService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Wizard steps after the password (ADR-032). Step 1 is the lock gate's setup screen. */
enum class OnboardingStep {
    /** Step 2: connect a Google account for Drive backups, or stay offline. */
    GOOGLE,

    /** Step 3: restore a backup (file / Drive) or start fresh. */
    RESTORE,

    /** Step 4: the picked backup needs the password it was sealed with. */
    BACKUP_PASSWORD,
}

/** What the Drive branch of step 3 knows so far. */
sealed interface DriveCheck {
    /** No account linked in step 2 — the Drive option is not offered. */
    data object NotLinked : DriveCheck

    /** Linked, but the Drive scope was not granted — explain, no listing. */
    data object NoAccess : DriveCheck

    data object Checking : DriveCheck

    /** The Drive folder holds no ClearTravel backup (said clearly, never silently). */
    data object None : DriveCheck

    data class Found(
        val backups: List<DriveBackupInfo>,
    ) : DriveCheck
}

/** Why the last step-2 / step-3 action did not go through (inline, retryable). */
enum class OnboardingError {
    /** Google linking failed or was refused (not configured is a separate UI state). */
    GOOGLE_LINK_FAILED,

    /** The backup is from a NEWER app version. */
    BACKUP_VERSION_TOO_NEW,

    /** The file is not a readable ClearTravel backup. */
    BACKUP_UNREADABLE,

    /** Read/download failed (SAF or Drive). */
    BACKUP_IO,
}

/** Where the backup being restored came from (needed to retry with a password). */
sealed interface RestoreSource {
    val uri: Uri

    data class LocalFile(
        override val uri: Uri,
        /** SAF display name (a `content://` URI's last segment is an opaque document id). */
        val displayName: String,
    ) : RestoreSource

    data class Drive(
        override val uri: Uri,
        val backup: DriveBackupInfo,
    ) : RestoreSource
}

data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.GOOGLE,
    val busy: Boolean = false,
    val error: OnboardingError? = null,
    /** Step 2: the account link as seen by the app (drives Connect / Continue). */
    val linkState: GoogleLinkState = GoogleLinkState.NotConfigured,
    /** Step 2: a scope-consent sheet must be launched via an IntentSender. */
    val consentIntent: PendingIntent? = null,
    /** Step 3: the Drive branch. */
    val drive: DriveCheck = DriveCheck.NotLinked,
    /** Step 4: the backup awaiting its source password. */
    val restoreSource: RestoreSource? = null,
    /** Step 4: the last password did not open the backup (inline error, retry). */
    val wrongBackupPassword: Boolean = false,
    /** Set once a restore has been applied — shown while the gate opens. */
    val restored: MergeSummary? = null,
) {
    val googleConfigured: Boolean get() = linkState !is GoogleLinkState.NotConfigured
    val googleLinked: Boolean get() = linkState is GoogleLinkState.Linked
}

/**
 * Wizard steps 2–4 of the first run (ADR-032). Runs after the password exists and
 * the encrypted store is open, so a restore can merge straight into Room through
 * the public [BackupManager] seam. The wizard finishes by clearing
 * [SettingsRepository.onboardingPending]; the lock gate then reveals the app.
 *
 * Resume semantics: this ViewModel always starts at [OnboardingStep.GOOGLE] — a
 * process death mid-wizard comes back here (after the unlock screen) and never
 * re-asks for the password. A linked account is remembered by the link store, so
 * step 2 then offers Continue instead of Connect.
 *
 * Restore semantics: a v1 plain ZIP or a same-salt envelope applies without a
 * password; a foreign envelope (the normal fresh-install case — new salt) is typed
 * `PasswordRequired` BEFORE anything is written and moves to step 4, where a wrong
 * password is an inline error and the right one merges the backup.
 */
@HiltViewModel
class OnboardingViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val accountManager: GoogleAccountManager,
        private val driveBackupService: DriveBackupService,
        private val backupManager: BackupManager,
        private val settingsRepository: SettingsRepository,
    ) : ViewModel() {
        private val local = MutableStateFlow(OnboardingUiState())

        val uiState: StateFlow<OnboardingUiState> =
            combine(local, accountManager.linkState, accountManager.featureSettings) { state, link, features ->
                val drive =
                    when {
                        link !is GoogleLinkState.Linked -> DriveCheck.NotLinked
                        !features.driveBackupEnabled -> DriveCheck.NoAccess
                        state.drive is DriveCheck.NotLinked || state.drive is DriveCheck.NoAccess -> DriveCheck.Checking
                        else -> state.drive
                    }
                state.copy(linkState = link, drive = drive)
            }.stateIn(viewModelScope, SharingStarted.Eagerly, OnboardingUiState())

        // ---- Step 2: Google ----

        /**
         * Links an account AND turns on Drive backups (the wizard's stated purpose —
         * listing/restoring Drive backups needs that scope). [activityContext] MUST be
         * an Activity context. Success advances to step 3; the user may need to
         * approve a consent sheet first ([consentIntent] → [onConsentResult]).
         */
        fun connectGoogle(activityContext: Context) {
            if (local.value.busy) return
            viewModelScope.launch {
                local.update { it.copy(busy = true, error = null) }
                val linked = accountManager.link(activityContext)
                if (linked.isFailure) {
                    local.update { it.copy(busy = false, error = linked.exceptionOrNull().toLinkError()) }
                    return@launch
                }
                accountManager
                    .setFeatureEnabled(GoogleFeature.DRIVE_BACKUP, enabled = true, activityContext = activityContext)
                    .onSuccess { advanceToRestore() }
                    .onFailure { failure ->
                        when (failure) {
                            is GoogleLinkException.NeedsScopeConsent ->
                                local.update { it.copy(busy = false, consentIntent = failure.pendingIntent) }
                            // Linked but without Drive access: still worth continuing —
                            // step 3 explains that Drive restore is unavailable.
                            else -> advanceToRestore()
                        }
                    }
            }
        }

        /** The screen has launched the consent IntentSender; stop re-launching it. */
        fun consentLaunched() {
            local.update { it.copy(consentIntent = null) }
        }

        /** The consent sheet returned; completes the Drive-backup toggle and moves on. */
        fun onConsentResult(resultIntent: Intent?) {
            viewModelScope.launch {
                local.update { it.copy(consentIntent = null, busy = true) }
                accountManager.completeScopeConsent(resultIntent)
                advanceToRestore()
            }
        }

        /** "Use offline" (or "Continue" when already linked): straight to step 3. */
        fun continueToRestore() {
            if (local.value.busy) return
            advanceToRestore()
        }

        private fun advanceToRestore() {
            local.update { it.copy(step = OnboardingStep.RESTORE, busy = false, error = null) }
            checkDrive()
        }

        // ---- Step 3: restore or start fresh ----

        /**
         * Lists the Drive backups when linked WITH Drive access; says so when none exist.
         * Reads the link sources directly (not the derived [uiState]) so it is correct
         * immediately after the link changed.
         */
        fun checkDrive() {
            viewModelScope.launch {
                if (accountManager.linkState.first() !is GoogleLinkState.Linked) return@launch
                if (!accountManager.featureSettings.first().driveBackupEnabled) return@launch
                local.update { it.copy(drive = DriveCheck.Checking) }
                val backups = driveBackupService.listBackups()
                local.update { it.copy(drive = if (backups.isEmpty()) DriveCheck.None else DriveCheck.Found(backups)) }
            }
        }

        /** A backup file picked through SAF. */
        fun restoreFromFile(uri: Uri) {
            if (local.value.busy) return
            viewModelScope.launch { attemptRestore(RestoreSource.LocalFile(uri, displayName(uri)), sourcePassword = null) }
        }

        /** `OpenableColumns.DISPLAY_NAME` of a SAF document, falling back to the last path segment. */
        private fun displayName(uri: Uri): String =
            try {
                context.contentResolver
                    .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            } catch (e: Exception) {
                null
            } ?: uri.lastPathSegment.orEmpty()

        /** A Drive backup picked from the list: download, then the same restore path. */
        fun restoreFromDrive(backup: DriveBackupInfo) {
            if (local.value.busy) return
            viewModelScope.launch {
                local.update { it.copy(busy = true, error = null) }
                val file =
                    try {
                        driveBackupService.downloadBackup(backup)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        local.update { it.copy(busy = false, error = OnboardingError.BACKUP_IO) }
                        return@launch
                    }
                attemptRestore(RestoreSource.Drive(Uri.fromFile(file), backup), sourcePassword = null)
            }
        }

        /** Nothing to restore — finish the wizard with an empty store. */
        fun startFresh() {
            if (local.value.busy) return
            viewModelScope.launch { finish() }
        }

        // ---- Step 4: backup password ----

        /** Retries the restore with the password the backup was sealed with. */
        fun submitBackupPassword(password: String) {
            val source = local.value.restoreSource ?: return
            if (local.value.busy || password.isEmpty()) return
            viewModelScope.launch { attemptRestore(source, password.toCharArray()) }
        }

        fun clearBackupPasswordError() {
            local.update { it.copy(wrongBackupPassword = false) }
        }

        /** Back from step 4 to step 3 (pick another backup or start fresh). */
        fun cancelRestore() {
            if (local.value.busy) return
            local.update { it.copy(step = OnboardingStep.RESTORE, restoreSource = null, wrongBackupPassword = false, error = null) }
        }

        private suspend fun attemptRestore(
            source: RestoreSource,
            sourcePassword: CharArray?,
        ) {
            local.update { it.copy(busy = true, error = null, wrongBackupPassword = false) }
            try {
                val summary = backupManager.importApply(source.uri, sourcePassword)
                local.update { it.copy(restored = summary, restoreSource = null) }
                finish()
            } catch (e: BackupException.PasswordRequired) {
                local.update { it.copy(busy = false, step = OnboardingStep.BACKUP_PASSWORD, restoreSource = source) }
            } catch (e: BackupException.WrongPassword) {
                local.update {
                    it.copy(
                        busy = false,
                        step = OnboardingStep.BACKUP_PASSWORD,
                        restoreSource = source,
                        wrongBackupPassword = true,
                    )
                }
            } catch (e: BackupException) {
                local.update {
                    it.copy(busy = false, step = OnboardingStep.RESTORE, restoreSource = null, error = e.toError())
                }
            }
        }

        /** Marks onboarding done; the lock gate observes the flag and reveals the app. */
        private suspend fun finish() {
            local.update { it.copy(busy = true) }
            settingsRepository.setOnboardingPending(false)
        }

        private fun Throwable?.toLinkError(): OnboardingError? =
            when (this) {
                is GoogleLinkException.Cancelled -> null // User backed out — no error line.
                else -> OnboardingError.GOOGLE_LINK_FAILED
            }

        private fun BackupException.toError(): OnboardingError =
            when (this) {
                is BackupException.UnsupportedSchemaVersion -> OnboardingError.BACKUP_VERSION_TOO_NEW
                is BackupException.Io -> OnboardingError.BACKUP_IO
                is BackupException.CorruptedBackup,
                is BackupException.Locked,
                is BackupException.PasswordRequired,
                is BackupException.WrongPassword,
                -> OnboardingError.BACKUP_UNREADABLE
            }
    }
