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
import com.itsluminous.cleartravel.core.testing.Fixtures
import com.itsluminous.cleartravel.core.testing.MainDispatcherRule
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
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

    override suspend fun exportToUri(uri: Uri): ExportResult {
        exportError?.let { throw it }
        exportedTo += uri
        localBackup = LocalBackupInfo("backup.zip", Fixtures.NOW, 42L)
        return ExportResult(createdAt = Fixtures.NOW, totalRows = 12, sizeBytes = 42L)
    }

    override suspend fun exportLatestToAppStorage(): ExportResult = exportToUri(Uri.EMPTY)

    override suspend fun importPreview(uri: Uri): ImportPreview {
        previewError?.let { throw it }
        return preview
    }

    override suspend fun importApply(uri: Uri): MergeSummary {
        applyError?.let { throw it }
        appliedFrom += uri
        return mergeSummary
    }

    override suspend fun latestLocalBackup(): LocalBackupInfo? = localBackup
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupRestoreViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val backupManager = FakeBackupManager()
    private val clock: Clock = Clock.fixed(Fixtures.NOW, ZoneOffset.UTC)

    private fun viewModel() = BackupRestoreViewModel(backupManager, clock)

    private val uri: Uri = Uri.parse("content://test/backup.zip")

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
}
