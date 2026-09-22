package com.itsluminous.cleartravel.core.google.backup

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.data.backup.BackupException
import com.itsluminous.cleartravel.core.data.backup.BackupManager
import com.itsluminous.cleartravel.core.data.backup.ExportResult
import com.itsluminous.cleartravel.core.data.backup.ImportPreview
import com.itsluminous.cleartravel.core.data.backup.LocalBackupInfo
import com.itsluminous.cleartravel.core.data.backup.MergeSummary
import com.itsluminous.cleartravel.core.google.FakeGoogleLinkStore
import com.itsluminous.cleartravel.core.google.auth.GoogleLinkSnapshot
import com.itsluminous.cleartravel.core.google.auth.GoogleNotAvailableException
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.File
import java.io.IOException
import java.time.Instant

/** Records every call in order — the runner's contract is ORDER (export, then upload). */
private class Trace {
    val calls = mutableListOf<String>()
}

private class RecordingBackupManager(
    private val trace: Trace,
    var exportError: BackupException? = null,
) : BackupManager {
    override suspend fun exportToUri(uri: Uri): ExportResult = exportLatestToAppStorage()

    override suspend fun exportLatestToAppStorage(): ExportResult {
        trace.calls += "export"
        exportError?.let { throw it }
        return ExportResult(Instant.EPOCH, 3, 42)
    }

    override suspend fun importPreview(
        uri: Uri,
        sourcePassword: CharArray?,
    ): ImportPreview = error("unused")

    override suspend fun importApply(
        uri: Uri,
        sourcePassword: CharArray?,
    ): MergeSummary = error("unused")

    override suspend fun latestLocalBackup(): LocalBackupInfo? = null
}

private class RecordingDriveBackupService(
    private val trace: Trace,
    var result: DriveBackupUploadResult = DriveBackupUploadResult.Uploaded,
) : DriveBackupService {
    override suspend fun uploadLatestBackup(): DriveBackupUploadResult {
        trace.calls += "upload"
        return result
    }

    override suspend fun listBackups(): List<DriveBackupInfo> = emptyList()

    override suspend fun downloadBackup(backup: DriveBackupInfo): File = error("unused")
}

class ScheduledBackupRunnerTest {
    private val trace = Trace()
    private val backupManager = RecordingBackupManager(trace)
    private val driveService = RecordingDriveBackupService(trace)
    private var unlocked = true

    private fun runner(link: GoogleLinkSnapshot = GoogleLinkSnapshot()) =
        ScheduledBackupRunner(
            isUnlocked = { unlocked },
            backupManager = backupManager,
            linkStore = FakeGoogleLinkStore(link),
            driveBackupService = driveService,
            notifyLocked = { trace.calls += "notify-locked" },
            deferUpload = { trace.calls += "defer-upload" },
        )

    private val linkedWithDriveBackup =
        GoogleLinkSnapshot(email = "traveler@example.com", driveBackupEnabled = true)

    @Test
    fun `locked vault posts the unlock nudge and touches nothing`() =
        runTest {
            unlocked = false

            val outcome = runner(linkedWithDriveBackup).run()

            assertThat(outcome).isEqualTo(ScheduledBackupOutcome.Locked)
            assertThat(trace.calls).containsExactly("notify-locked")
        }

    @Test
    fun `drive backup disabled exports locally and never uploads`() =
        runTest {
            val outcome = runner(GoogleLinkSnapshot(email = "traveler@example.com", driveBackupEnabled = false)).run()

            assertThat(outcome).isEqualTo(ScheduledBackupOutcome.LocalOnly)
            assertThat(trace.calls).containsExactly("export")
        }

    @Test
    fun `drive backup enabled but unlinked exports locally only`() =
        runTest {
            val outcome = runner(GoogleLinkSnapshot(email = null, driveBackupEnabled = true)).run()

            assertThat(outcome).isEqualTo(ScheduledBackupOutcome.LocalOnly)
            assertThat(trace.calls).containsExactly("export")
        }

    @Test
    fun `drive backup enabled and linked exports first, then uploads`() =
        runTest {
            val outcome = runner(linkedWithDriveBackup).run()

            assertThat(outcome).isEqualTo(ScheduledBackupOutcome.Uploaded)
            assertThat(trace.calls).containsExactly("export", "upload").inOrder()
        }

    @Test
    fun `export failure is reported and the upload is never attempted`() =
        runTest {
            backupManager.exportError = BackupException.Io()

            val outcome = runner(linkedWithDriveBackup).run()

            assertThat(outcome).isInstanceOf(ScheduledBackupOutcome.ExportFailed::class.java)
            assertThat(trace.calls).containsExactly("export")
        }

    @Test
    fun `transient upload failure hands the sealed file to the drive worker instead of re-exporting`() =
        runTest {
            driveService.result = DriveBackupUploadResult.Failed(IOException("offline"))

            val outcome = runner(linkedWithDriveBackup).run()

            assertThat(outcome).isInstanceOf(ScheduledBackupOutcome.UploadDeferred::class.java)
            assertThat(trace.calls).containsExactly("export", "upload", "defer-upload").inOrder()
        }

    @Test
    fun `upload without a google token is quiet local-only, nothing deferred`() =
        runTest {
            driveService.result = DriveBackupUploadResult.Failed(GoogleNotAvailableException())

            val outcome = runner(linkedWithDriveBackup).run()

            assertThat(outcome).isEqualTo(ScheduledBackupOutcome.LocalOnly)
            assertThat(trace.calls).containsExactly("export", "upload").inOrder()
        }
}
