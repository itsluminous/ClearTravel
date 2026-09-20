package com.itsluminous.cleartravel.feature.menu

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsluminous.cleartravel.core.data.backup.BackupException
import com.itsluminous.cleartravel.core.data.backup.BackupFileNames
import com.itsluminous.cleartravel.core.data.backup.BackupManager
import com.itsluminous.cleartravel.core.data.backup.ImportPreview
import com.itsluminous.cleartravel.core.data.backup.LocalBackupInfo
import com.itsluminous.cleartravel.core.data.backup.MergeSummary
import com.itsluminous.cleartravel.core.google.auth.GoogleAccountManager
import com.itsluminous.cleartravel.core.google.auth.GoogleLinkState
import com.itsluminous.cleartravel.core.google.auth.GoogleSyncScheduler
import com.itsluminous.cleartravel.core.google.backup.DriveBackupInfo
import com.itsluminous.cleartravel.core.google.backup.DriveBackupService
import com.itsluminous.cleartravel.core.google.backup.FreshInstallDetector
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import javax.inject.Inject

/** One-shot events from [BackupRestoreViewModel] — the screen maps each to a snackbar. */
sealed interface BackupRestoreEvent {
    /** Export finished; the file was written to the chosen location + app storage. */
    data class ExportDone(
        val totalRows: Int,
    ) : BackupRestoreEvent

    /** Import merge finished with per-row accounting for the snackbar. */
    data class ImportDone(
        val summary: MergeSummary,
    ) : BackupRestoreEvent

    /** The backup was written by a NEWER app version (typed graceful rejection). */
    data object BackupVersionTooNew : BackupRestoreEvent

    /** The chosen file is not a readable ClearTravel backup. */
    data object BackupUnreadable : BackupRestoreEvent

    /** The destination/source could not be read or written. */
    data object IoFailed : BackupRestoreEvent

    /** A Drive backup could not be downloaded (offline / revoked). */
    data object DriveDownloadFailed : BackupRestoreEvent
}

/**
 * State + actions for Menu → Backup & Restore. Export/import both run through the
 * public [BackupManager] seam (ADR-015); import is preview-then-confirm: choosing a
 * file only parses the manifest into [UiState.pendingImport], and the merge runs
 * only after [confirmImport].
 *
 * Drive side (spec features 5+6, ADR-016): after every successful export the newest
 * app-storage backup is queued for Drive upload (worker-side gated on the toggle);
 * when linked, the screen lists the Drive backups for manual restore, and a
 * fresh-ish install (zero trips/journeys/checklists) with a Drive backup available
 * gets a one-time restore prompt showing its date + size.
 */
@HiltViewModel
class BackupRestoreViewModel
    @Inject
    constructor(
        private val backupManager: BackupManager,
        private val clock: Clock,
        private val accountManager: GoogleAccountManager,
        private val driveBackupService: DriveBackupService,
        private val freshInstallDetector: FreshInstallDetector,
        private val googleSyncScheduler: GoogleSyncScheduler,
    ) : ViewModel() {
        /** An import awaiting user confirmation (dialog with date + counts). */
        data class PendingImport(
            val uri: Uri,
            val preview: ImportPreview,
        )

        data class UiState(
            /** True while an export or import is running (buttons disabled). */
            val inProgress: Boolean = false,
            /** Newest backup in app storage; null = never backed up. */
            val lastBackup: LocalBackupInfo? = null,
            val pendingImport: PendingImport? = null,
            /** A Google account is linked (drives the Drive card's state). */
            val driveLinked: Boolean = false,
            /** Backups in the Drive folder, newest first (empty when unlinked). */
            val driveBackups: List<DriveBackupInfo> = emptyList(),
            /** The Drive-backup picker dialog is open. */
            val showDriveList: Boolean = false,
            /** Non-null: offer the fresh-install restore of this backup (date + size). */
            val freshRestorePrompt: DriveBackupInfo? = null,
        )

        private val _uiState = MutableStateFlow(UiState())
        val uiState: StateFlow<UiState> = _uiState.asStateFlow()

        private val eventChannel = Channel<BackupRestoreEvent>(Channel.BUFFERED)
        val events: Flow<BackupRestoreEvent> = eventChannel.receiveAsFlow()

        /** The fresh-install prompt is offered at most once per screen visit. */
        private var freshPromptShown = false

        init {
            refreshLastBackup()
            viewModelScope.launch {
                accountManager.linkState.collect { state ->
                    val linked = state is GoogleLinkState.Linked
                    _uiState.update { it.copy(driveLinked = linked) }
                    if (linked) refreshDriveBackups() else _uiState.update { it.copy(driveBackups = emptyList()) }
                }
            }
        }

        /** SAF CreateDocument suggestion: `cleartravel-backup-YYYYMMDD-HHmm.zip`. */
        fun suggestedExportFileName(): String = BackupFileNames.suggestedExportName(clock.instant())

        /** Exports a backup to the SAF [uri] the user picked. */
        fun export(uri: Uri) {
            if (_uiState.value.inProgress) return
            viewModelScope.launch {
                _uiState.update { it.copy(inProgress = true) }
                try {
                    val result = backupManager.exportToUri(uri)
                    eventChannel.send(BackupRestoreEvent.ExportDone(result.totalRows))
                    // Backup-to-Drive hook (ADR-016): the worker uploads the fresh
                    // app-storage copy and prunes to 5; it self-skips when the toggle
                    // is off or no account is linked, so the call is unconditional.
                    googleSyncScheduler.scheduleBackupUpload()
                } catch (e: BackupException) {
                    eventChannel.send(e.toEvent())
                } finally {
                    _uiState.update { it.copy(inProgress = false) }
                    refreshLastBackup()
                }
            }
        }

        /** Parses the manifest of the picked file into a confirmation preview. */
        fun requestImport(uri: Uri) {
            if (_uiState.value.inProgress) return
            viewModelScope.launch {
                _uiState.update { it.copy(inProgress = true) }
                try {
                    val preview = backupManager.importPreview(uri)
                    _uiState.update { it.copy(pendingImport = PendingImport(uri, preview)) }
                } catch (e: BackupException) {
                    eventChannel.send(e.toEvent())
                } finally {
                    _uiState.update { it.copy(inProgress = false) }
                }
            }
        }

        /** Applies the previewed import — the LWW merge, never a wipe (ADR-015). */
        fun confirmImport() {
            val pending = _uiState.value.pendingImport ?: return
            viewModelScope.launch {
                _uiState.update { it.copy(inProgress = true, pendingImport = null) }
                try {
                    val summary = backupManager.importApply(pending.uri)
                    eventChannel.send(BackupRestoreEvent.ImportDone(summary))
                } catch (e: BackupException) {
                    eventChannel.send(e.toEvent())
                } finally {
                    _uiState.update { it.copy(inProgress = false) }
                }
            }
        }

        fun dismissImport() {
            _uiState.update { it.copy(pendingImport = null) }
        }

        fun openDriveList() {
            _uiState.update { it.copy(showDriveList = true) }
        }

        fun dismissDriveList() {
            _uiState.update { it.copy(showDriveList = false) }
        }

        /**
         * Manual restore-from-Drive: downloads the picked backup, then feeds it
         * through the normal preview-then-confirm import flow (the documented
         * [BackupManager] seam — no engine changes).
         */
        fun restoreFromDrive(backup: DriveBackupInfo) {
            if (_uiState.value.inProgress) return
            viewModelScope.launch {
                _uiState.update { it.copy(inProgress = true, showDriveList = false) }
                val uri = downloadToUri(backup)
                _uiState.update { it.copy(inProgress = false) }
                if (uri != null) requestImport(uri) else eventChannel.send(BackupRestoreEvent.DriveDownloadFailed)
            }
        }

        /** Fresh-install prompt confirm: download + apply the offered backup directly. */
        fun confirmFreshRestore() {
            val backup = _uiState.value.freshRestorePrompt ?: return
            viewModelScope.launch {
                _uiState.update { it.copy(inProgress = true, freshRestorePrompt = null) }
                try {
                    val uri = downloadToUri(backup)
                    if (uri == null) {
                        eventChannel.send(BackupRestoreEvent.DriveDownloadFailed)
                    } else {
                        val summary = backupManager.importApply(uri)
                        eventChannel.send(BackupRestoreEvent.ImportDone(summary))
                    }
                } catch (e: BackupException) {
                    eventChannel.send(e.toEvent())
                } finally {
                    _uiState.update { it.copy(inProgress = false) }
                }
            }
        }

        fun dismissFreshRestore() {
            _uiState.update { it.copy(freshRestorePrompt = null) }
        }

        private suspend fun refreshDriveBackups() {
            val backups = driveBackupService.listBackups()
            _uiState.update { it.copy(driveBackups = backups) }
            if (!freshPromptShown && backups.isNotEmpty() && freshInstallDetector.isFreshInstall()) {
                freshPromptShown = true
                _uiState.update { it.copy(freshRestorePrompt = backups.first()) }
            }
        }

        private suspend fun downloadToUri(backup: DriveBackupInfo): Uri? =
            try {
                Uri.fromFile(driveBackupService.downloadBackup(backup))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }

        private fun refreshLastBackup() {
            viewModelScope.launch {
                _uiState.update { it.copy(lastBackup = backupManager.latestLocalBackup()) }
            }
        }

        private fun BackupException.toEvent(): BackupRestoreEvent =
            when (this) {
                is BackupException.UnsupportedSchemaVersion -> BackupRestoreEvent.BackupVersionTooNew
                is BackupException.CorruptedBackup -> BackupRestoreEvent.BackupUnreadable
                is BackupException.Io -> BackupRestoreEvent.IoFailed
            }
    }
