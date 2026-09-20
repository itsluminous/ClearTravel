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
import dagger.hilt.android.lifecycle.HiltViewModel
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
}

/**
 * State + actions for Menu → Backup & Restore. Export/import both run through the
 * public [BackupManager] seam (ADR-015); import is preview-then-confirm: choosing a
 * file only parses the manifest into [UiState.pendingImport], and the merge runs
 * only after [confirmImport].
 */
@HiltViewModel
class BackupRestoreViewModel
    @Inject
    constructor(
        private val backupManager: BackupManager,
        private val clock: Clock,
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
        )

        private val _uiState = MutableStateFlow(UiState())
        val uiState: StateFlow<UiState> = _uiState.asStateFlow()

        private val eventChannel = Channel<BackupRestoreEvent>(Channel.BUFFERED)
        val events: Flow<BackupRestoreEvent> = eventChannel.receiveAsFlow()

        init {
            refreshLastBackup()
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
