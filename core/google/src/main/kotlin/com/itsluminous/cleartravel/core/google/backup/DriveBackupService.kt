package com.itsluminous.cleartravel.core.google.backup

import com.itsluminous.cleartravel.core.data.backup.BackupManager
import com.itsluminous.cleartravel.core.google.auth.GoogleLinkStore
import com.itsluminous.cleartravel.core.google.drive.DriveClient
import com.itsluminous.cleartravel.core.google.drive.DriveFolderResolver
import kotlinx.coroutines.CancellationException
import java.io.File
import java.time.Instant

/** One ClearTravel backup ZIP living in the app's Drive folder. */
data class DriveBackupInfo(
    val fileId: String,
    val fileName: String,
    val createdAt: Instant,
    val sizeBytes: Long,
)

/** Outcome of a backup upload pass. */
sealed interface DriveBackupUploadResult {
    /** Backups-to-Drive disabled / not linked / nothing exported yet. */
    data object Skipped : DriveBackupUploadResult

    data object Uploaded : DriveBackupUploadResult

    /** Transient failure — the worker retries with backoff. */
    data class Failed(
        val cause: Throwable,
    ) : DriveBackupUploadResult
}

/**
 * Backup-to-Drive (spec feature 6): after every successful export the newest
 * app-storage backup ZIP is ALSO uploaded into the "ClearTravel" Drive folder, kept
 * to the [MAX_DRIVE_BACKUPS] most recent (older ones pruned). Also the read side of
 * the fresh-install restore: list the Drive backups and download one for
 * `importPreview`/`importApply` (the documented [BackupManager] seam — no engine
 * changes).
 */
interface DriveBackupService {
    /** Uploads the newest app-storage backup and prunes Drive to the last [MAX_DRIVE_BACKUPS]. */
    suspend fun uploadLatestBackup(): DriveBackupUploadResult

    /** Backups in the Drive folder, newest first; empty when unlinked/unreachable (graceful). */
    suspend fun listBackups(): List<DriveBackupInfo>

    /** Downloads one backup into the app cache and returns the local file. */
    suspend fun downloadBackup(backup: DriveBackupInfo): File

    companion object {
        const val MAX_DRIVE_BACKUPS = 5

        /** Shared name prefix of every backup ZIP (matches `BackupFileNames`). */
        const val BACKUP_NAME_PREFIX = "cleartravel-backup-"
    }
}

/** [DriveBackupService] composing [BackupManager] output with the Drive client. */
class DefaultDriveBackupService(
    private val linkStore: GoogleLinkStore,
    private val backupManager: BackupManager,
    private val driveClient: DriveClient,
    private val folderResolver: DriveFolderResolver,
    /** `filesDir/backups` — where `exportLatestToAppStorage` keeps the newest ZIPs. */
    private val backupsDir: File,
    /** Scratch space for downloaded backups (`cacheDir/drive-backups`). */
    private val downloadDir: File,
) : DriveBackupService {
    override suspend fun uploadLatestBackup(): DriveBackupUploadResult {
        val snapshot = linkStore.current()
        if (!snapshot.isLinked || !snapshot.driveBackupEnabled) return DriveBackupUploadResult.Skipped
        val latest = backupManager.latestLocalBackup() ?: return DriveBackupUploadResult.Skipped
        val file = File(backupsDir, latest.fileName)
        if (!file.isFile) return DriveBackupUploadResult.Skipped
        return try {
            val folderId = folderResolver.ensureFolder()
            val alreadyUploaded =
                driveClient
                    .listFiles(folderId, DriveBackupService.BACKUP_NAME_PREFIX)
                    .any { it.name == latest.fileName }
            if (!alreadyUploaded) {
                driveClient.uploadFile(
                    name = latest.fileName,
                    mimeType = "application/zip",
                    parentId = folderId,
                    sourceFile = file,
                )
            }
            prune(folderId)
            DriveBackupUploadResult.Uploaded
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DriveBackupUploadResult.Failed(e)
        }
    }

    override suspend fun listBackups(): List<DriveBackupInfo> {
        val snapshot = linkStore.current()
        if (!snapshot.isLinked) return emptyList()
        return try {
            // Listing must never CREATE the folder — a fresh link with no uploads yet
            // simply has no backups. It searches EVERY app-visible folder with the app's
            // name (ADR-038): a fresh install has no cached id, and an account that
            // ended up with duplicate folders before they converged may keep its
            // backups in any of them.
            folderResolver
                .existingFolderIds()
                .flatMap { folderId -> driveClient.listFiles(folderId, DriveBackupService.BACKUP_NAME_PREFIX) }
                .distinctBy { it.fileId }
                .map { DriveBackupInfo(it.fileId, it.name, it.createdAt, it.sizeBytes) }
                .sortedByDescending { it.fileName }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun downloadBackup(backup: DriveBackupInfo): File {
        val target = File(downloadDir.apply { mkdirs() }, backup.fileName)
        driveClient.downloadFile(backup.fileId, target)
        return target
    }

    /** Keeps the [DriveBackupService.MAX_DRIVE_BACKUPS] newest backups (by timestamped name), deletes the rest. */
    private suspend fun prune(folderId: String) {
        val backups =
            driveClient
                .listFiles(folderId, DriveBackupService.BACKUP_NAME_PREFIX)
                .sortedByDescending { it.name }
        backups.drop(DriveBackupService.MAX_DRIVE_BACKUPS).forEach { driveClient.deleteFile(it.fileId) }
    }
}
