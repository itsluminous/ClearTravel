package com.itsluminous.cleartravel.feature.menu

import android.net.Uri
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.data.backup.BackupException
import com.itsluminous.cleartravel.core.data.backup.BackupManager
import com.itsluminous.cleartravel.core.data.backup.ExportResult
import com.itsluminous.cleartravel.core.data.backup.ImportPreview
import com.itsluminous.cleartravel.core.data.backup.LocalBackupInfo
import com.itsluminous.cleartravel.core.data.backup.MergeSummary
import com.itsluminous.cleartravel.core.google.auth.GoogleLinkState
import com.itsluminous.cleartravel.core.google.auth.GoogleSyncScheduler
import com.itsluminous.cleartravel.core.google.backup.DriveBackupInfo
import com.itsluminous.cleartravel.core.google.backup.DriveBackupService
import com.itsluminous.cleartravel.core.google.backup.DriveBackupUploadResult
import com.itsluminous.cleartravel.core.google.backup.FreshInstallDetector
import com.itsluminous.cleartravel.core.google.work.ScheduledBackupScheduler
import com.itsluminous.cleartravel.core.model.BackupSchedule
import com.itsluminous.cleartravel.core.testing.Fixtures
import com.itsluminous.cleartravel.core.testing.MainDispatcherRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.time.Clock
import java.time.ZoneOffset

/** Configurable in-memory [BackupManager] — records calls, throws on demand. */
private class FakeBackupManager : BackupManager {
    var localBackup: LocalBackupInfo? = null
    var exportError: BackupException? = null
    var previewError: BackupException? = null
    var applyError: BackupException? = null
    var preview =
        ImportPreview(
            schemaVersion = 1,
            appVersion = "0.1.0",
            createdAt = Fixtures.NOW,
            entityCounts = mapOf("trips" to 2, "train_tickets" to 1),
        )
    var mergeSummary = MergeSummary(inserted = 3, updated = 1, skipped = 2)
    val exportedTo = mutableListOf<Uri>()
    val appliedFrom = mutableListOf<Uri>()

    /** Non-null: the backup is a foreign v2 envelope that only this password opens (ADR-031). */
    var requiredPassword: String? = null
    val passwordsSeen = mutableListOf<String?>()

    private fun gate(sourcePassword: CharArray?) {
        val required = requiredPassword ?: return
        passwordsSeen += sourcePassword?.concatToString()
        when {
            sourcePassword == null -> throw BackupException.PasswordRequired()
            sourcePassword.concatToString() != required -> throw BackupException.WrongPassword()
            else -> requiredPassword = null // the manager adopts the proven key
        }
    }

    override suspend fun exportToUri(uri: Uri): ExportResult {
        exportError?.let { throw it }
        exportedTo += uri
        localBackup = LocalBackupInfo("backup.zip", Fixtures.NOW, 42L)
        return ExportResult(createdAt = Fixtures.NOW, totalRows = 12, sizeBytes = 42L)
    }

    override suspend fun exportLatestToAppStorage(): ExportResult = exportToUri(Uri.EMPTY)

    override suspend fun importPreview(
        uri: Uri,
        sourcePassword: CharArray?,
    ): ImportPreview {
        previewError?.let { throw it }
        gate(sourcePassword)
        return preview
    }

    override suspend fun importApply(
        uri: Uri,
        sourcePassword: CharArray?,
    ): MergeSummary {
        applyError?.let { throw it }
        gate(sourcePassword)
        appliedFrom += uri
        return mergeSummary
    }

    override suspend fun latestLocalBackup(): LocalBackupInfo? = localBackup
}

/** Scripted [DriveBackupService] for the ViewModel's Drive rows. */
private class FakeDriveBackupService : DriveBackupService {
    var backups: List<DriveBackupInfo> = emptyList()
    var downloadFile: File? = null

    override suspend fun uploadLatestBackup(): DriveBackupUploadResult = DriveBackupUploadResult.Skipped

    override suspend fun listBackups(): List<DriveBackupInfo> = backups

    override suspend fun downloadBackup(backup: DriveBackupInfo): File = downloadFile ?: throw IOException("offline")
}

private class FakeFreshInstallDetector(
    var fresh: Boolean = false,
) : FreshInstallDetector {
    override suspend fun isFreshInstall(): Boolean = fresh
}

/** Recording [GoogleSyncScheduler] — verifies the export → Drive upload hook. */
private class RecordingSyncScheduler : GoogleSyncScheduler {
    var backupUploads = 0

    override fun scheduleCalendarSync() = Unit

    override fun cancelCalendarSync() = Unit

    override fun scheduleDriveUploads() = Unit

    override fun cancelDriveUploads() = Unit

    override fun scheduleBackupUpload() {
        backupUploads++
    }

    override fun scheduleDisconnectCleanup(calendarId: String) = Unit
}

/** Recording [ScheduledBackupScheduler] — the ADR-037 apply-on-change hook. */
private class RecordingScheduledBackupScheduler : ScheduledBackupScheduler {
    val applied = mutableListOf<BackupSchedule>()

    override fun apply(schedule: BackupSchedule) {
        applied += schedule
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupRestoreViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val backupManager = FakeBackupManager()
    private val clock: Clock = Clock.fixed(Fixtures.NOW, ZoneOffset.UTC)
    private val googleManager = FakeGoogleAccountManager()
    private val driveService = FakeDriveBackupService()
    private val detector = FakeFreshInstallDetector()
    private val scheduler = RecordingSyncScheduler()
    private val settings = FakeSettingsRepository()
    private val backupScheduler = RecordingScheduledBackupScheduler()

    private fun viewModel() =
        BackupRestoreViewModel(
            backupManager,
            clock,
            googleManager,
            driveService,
            detector,
            scheduler,
            settings,
            backupScheduler,
        )

    private val uri: Uri = Uri.parse("content://test/backup.zip")

    private val driveBackup =
        DriveBackupInfo(
            fileId = "file-1",
            fileName = "cleartravel-backup-20260101-0101.zip",
            createdAt = Fixtures.NOW,
            sizeBytes = 42L,
        )

    private fun linkGoogle() {
        googleManager.state.value = GoogleLinkState.Linked("traveler@example.com", emptySet())
    }

    @Test
    fun `suggested export file name follows the spec pattern`() {
        val name = viewModel().suggestedExportFileName()

        assertThat(name).matches("cleartravel-backup-\\d{8}-\\d{4}\\.zip")
    }

    @Test
    fun `export success emits ExportDone with row count and refreshes last backup`() =
        runTest {
            val viewModel = viewModel()
            viewModel.events.test {
                viewModel.export(uri)

                assertThat(awaitItem()).isEqualTo(BackupRestoreEvent.ExportDone(totalRows = 12))
            }
            assertThat(backupManager.exportedTo).containsExactly(uri)
            assertThat(
                viewModel.uiState.value.lastBackup
                    ?.fileName,
            ).isEqualTo("backup.zip")
            assertThat(viewModel.uiState.value.inProgress).isFalse()
        }

    @Test
    fun `export failure emits IoFailed`() =
        runTest {
            backupManager.exportError = BackupException.Io()
            val viewModel = viewModel()
            viewModel.events.test {
                viewModel.export(uri)

                assertThat(awaitItem()).isEqualTo(BackupRestoreEvent.IoFailed)
            }
            assertThat(viewModel.uiState.value.inProgress).isFalse()
        }

    @Test
    fun `requestImport parses preview into pendingImport without applying`() =
        runTest {
            val viewModel = viewModel()

            viewModel.requestImport(uri)

            val pending = viewModel.uiState.value.pendingImport
            assertThat(pending?.uri).isEqualTo(uri)
            assertThat(pending?.preview?.totalRows).isEqualTo(3)
            assertThat(backupManager.appliedFrom).isEmpty()
        }

    @Test
    fun `confirmImport applies the merge and emits ImportDone with counts`() =
        runTest {
            val viewModel = viewModel()
            viewModel.requestImport(uri)

            viewModel.events.test {
                viewModel.confirmImport()

                val event = awaitItem() as BackupRestoreEvent.ImportDone
                assertThat(event.summary).isEqualTo(MergeSummary(inserted = 3, updated = 1, skipped = 2))
            }
            assertThat(backupManager.appliedFrom).containsExactly(uri)
            assertThat(viewModel.uiState.value.pendingImport).isNull()
        }

    @Test
    fun `dismissImport clears the pending preview and applies nothing`() =
        runTest {
            val viewModel = viewModel()
            viewModel.requestImport(uri)

            viewModel.dismissImport()

            assertThat(viewModel.uiState.value.pendingImport).isNull()
            assertThat(backupManager.appliedFrom).isEmpty()
        }

    @Test
    fun `newer schema version surfaces as BackupVersionTooNew`() =
        runTest {
            backupManager.previewError = BackupException.UnsupportedSchemaVersion(found = 2)
            val viewModel = viewModel()
            viewModel.events.test {
                viewModel.requestImport(uri)

                assertThat(awaitItem()).isEqualTo(BackupRestoreEvent.BackupVersionTooNew)
            }
            assertThat(viewModel.uiState.value.pendingImport).isNull()
        }

    @Test
    fun `corrupted backup surfaces as BackupUnreadable`() =
        runTest {
            backupManager.previewError = BackupException.CorruptedBackup()
            val viewModel = viewModel()
            viewModel.events.test {
                viewModel.requestImport(uri)

                assertThat(awaitItem()).isEqualTo(BackupRestoreEvent.BackupUnreadable)
            }
        }

    @Test
    fun `apply failure emits typed event and clears progress`() =
        runTest {
            backupManager.applyError = BackupException.CorruptedBackup()
            val viewModel = viewModel()
            viewModel.requestImport(uri)
            viewModel.events.test {
                viewModel.confirmImport()

                assertThat(awaitItem()).isEqualTo(BackupRestoreEvent.BackupUnreadable)
            }
            assertThat(viewModel.uiState.value.inProgress).isFalse()
        }

    @Test
    fun `last backup info loads on init`() =
        runTest {
            backupManager.localBackup = LocalBackupInfo("existing.zip", Fixtures.NOW, 7L)

            val viewModel = viewModel()

            assertThat(
                viewModel.uiState.value.lastBackup
                    ?.fileName,
            ).isEqualTo("existing.zip")
        }

    @Test
    fun `successful export schedules the drive backup upload`() =
        runTest {
            val viewModel = viewModel()
            viewModel.events.test {
                viewModel.export(uri)
                awaitItem()
            }

            assertThat(scheduler.backupUploads).isEqualTo(1)
        }

    @Test
    fun `failed export does not schedule a drive upload`() =
        runTest {
            backupManager.exportError = BackupException.Io()
            val viewModel = viewModel()
            viewModel.events.test {
                viewModel.export(uri)
                awaitItem()
            }

            assertThat(scheduler.backupUploads).isEqualTo(0)
        }

    @Test
    fun `linking google loads the drive backup list`() =
        runTest {
            driveService.backups = listOf(driveBackup)
            linkGoogle()

            val viewModel = viewModel()

            assertThat(viewModel.uiState.value.driveLinked).isTrue()
            assertThat(viewModel.uiState.value.driveBackups).containsExactly(driveBackup)
        }

    @Test
    fun `fresh install with a drive backup prompts once with that backup`() =
        runTest {
            driveService.backups = listOf(driveBackup)
            detector.fresh = true
            linkGoogle()

            val viewModel = viewModel()

            assertThat(viewModel.uiState.value.freshRestorePrompt).isEqualTo(driveBackup)

            viewModel.dismissFreshRestore()
            assertThat(viewModel.uiState.value.freshRestorePrompt).isNull()
        }

    @Test
    fun `no prompt when the install is not fresh or drive is empty`() =
        runTest {
            detector.fresh = false
            driveService.backups = listOf(driveBackup)
            linkGoogle()
            assertThat(viewModel().uiState.value.freshRestorePrompt).isNull()

            detector.fresh = true
            driveService.backups = emptyList()
            assertThat(viewModel().uiState.value.freshRestorePrompt).isNull()
        }

    @Test
    fun `confirming the fresh restore downloads and applies the backup`() =
        runTest {
            driveService.backups = listOf(driveBackup)
            driveService.downloadFile = File.createTempFile("cleartravel-test", ".zip")
            detector.fresh = true
            linkGoogle()
            val viewModel = viewModel()

            viewModel.events.test {
                viewModel.confirmFreshRestore()

                val event = awaitItem() as BackupRestoreEvent.ImportDone
                assertThat(event.summary.inserted).isEqualTo(3)
            }
            assertThat(backupManager.appliedFrom).hasSize(1)
            assertThat(viewModel.uiState.value.freshRestorePrompt).isNull()
        }

    @Test
    fun `manual restore from drive feeds the normal preview-confirm flow`() =
        runTest {
            driveService.backups = listOf(driveBackup)
            driveService.downloadFile = File.createTempFile("cleartravel-test", ".zip")
            linkGoogle()
            val viewModel = viewModel()
            viewModel.openDriveList()

            viewModel.restoreFromDrive(driveBackup)

            assertThat(viewModel.uiState.value.showDriveList).isFalse()
            assertThat(viewModel.uiState.value.pendingImport).isNotNull()
            assertThat(backupManager.appliedFrom).isEmpty()
        }

    @Test
    fun `a failed drive download surfaces as DriveDownloadFailed`() =
        runTest {
            driveService.backups = listOf(driveBackup)
            driveService.downloadFile = null
            linkGoogle()
            val viewModel = viewModel()

            viewModel.events.test {
                viewModel.restoreFromDrive(driveBackup)

                assertThat(awaitItem()).isEqualTo(BackupRestoreEvent.DriveDownloadFailed)
            }
        }

    @Test
    fun `foreign v2 backup asks for the source password, rejects a wrong one, then previews and applies silently`() =
        runTest {
            backupManager.requiredPassword = "old-secret"
            val viewModel = viewModel()

            viewModel.requestImport(uri)
            val prompt = viewModel.uiState.value.passwordPrompt
            assertThat(prompt?.uri).isEqualTo(uri)
            assertThat(prompt?.step).isEqualTo(BackupRestoreViewModel.PasswordStep.PREVIEW)
            assertThat(viewModel.uiState.value.pendingImport).isNull()

            viewModel.events.test {
                viewModel.submitSourcePassword("nope")
                assertThat(awaitItem()).isEqualTo(BackupRestoreEvent.WrongBackupPassword)
                assertThat(viewModel.uiState.value.passwordPrompt).isNotNull() // prompt stays

                viewModel.submitSourcePassword("old-secret")
                assertThat(viewModel.uiState.value.passwordPrompt).isNull()
                assertThat(
                    viewModel.uiState.value.pendingImport
                        ?.preview
                        ?.totalRows,
                ).isEqualTo(3)

                viewModel.confirmImport() // adopted key: no second prompt
                assertThat(awaitItem()).isEqualTo(BackupRestoreEvent.ImportDone(backupManager.mergeSummary))
            }
            assertThat(backupManager.passwordsSeen).containsExactly(null, "nope", "old-secret").inOrder()
        }

    @Test
    fun `fresh-install drive restore asks for the previous install's password before applying`() =
        runTest {
            backupManager.requiredPassword = "previous"
            detector.fresh = true
            driveService.backups = listOf(driveBackup)
            driveService.downloadFile = File.createTempFile("cleartravel-test", ".zip")
            linkGoogle()
            val viewModel = viewModel()
            assertThat(viewModel.uiState.value.freshRestorePrompt).isEqualTo(driveBackup)

            viewModel.confirmFreshRestore()

            assertThat(
                viewModel.uiState.value.passwordPrompt
                    ?.step,
            ).isEqualTo(BackupRestoreViewModel.PasswordStep.APPLY)
            viewModel.events.test {
                viewModel.submitSourcePassword("previous")
                assertThat(awaitItem()).isEqualTo(BackupRestoreEvent.ImportDone(backupManager.mergeSummary))
            }
            assertThat(backupManager.appliedFrom).hasSize(1)
        }

    @Test
    fun `backup schedule defaults to off and mirrors the persisted setting`() =
        runTest {
            assertThat(viewModel().uiState.value.schedule).isEqualTo(BackupSchedule.OFF)

            settings.setBackupSchedule(BackupSchedule.WEEKLY)

            assertThat(viewModel().uiState.value.schedule).isEqualTo(BackupSchedule.WEEKLY)
        }

    @Test
    fun `picking a cadence persists it and applies the periodic job, off cancels`() =
        runTest {
            val viewModel = viewModel()

            viewModel.setBackupSchedule(BackupSchedule.DAILY)
            assertThat(settings.backupSchedule.first()).isEqualTo(BackupSchedule.DAILY)
            assertThat(viewModel.uiState.value.schedule).isEqualTo(BackupSchedule.DAILY)

            viewModel.setBackupSchedule(BackupSchedule.OFF)
            assertThat(settings.backupSchedule.first()).isEqualTo(BackupSchedule.OFF)
            assertThat(backupScheduler.applied).containsExactly(BackupSchedule.DAILY, BackupSchedule.OFF).inOrder()
        }
}
