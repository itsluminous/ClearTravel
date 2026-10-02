package com.itsluminous.cleartravel.core.google.backup

import com.itsluminous.cleartravel.core.data.backup.BackupManager
import com.itsluminous.cleartravel.core.google.auth.GoogleLinkStore
import com.itsluminous.cleartravel.core.google.auth.GoogleNotAvailableException
import kotlinx.coroutines.CancellationException

/** What one automatic backup pass did (ADR-037) — the worker maps it to a verdict. */
sealed interface ScheduledBackupOutcome {
    /** The vault is locked (and the ADR-043 background key, if any, could not open it): nothing ran, silently. */
    data object Locked : ScheduledBackupOutcome

    /** Local backup written; Drive was not attempted (toggle off / not linked). */
    data object LocalOnly : ScheduledBackupOutcome

    /** Local backup written AND uploaded to Drive. */
    data object Uploaded : ScheduledBackupOutcome

    /** Local backup written; the Drive upload failed transiently and was handed to the Drive worker. */
    data class UploadDeferred(
        val cause: Throwable,
    ) : ScheduledBackupOutcome

    /** The local export itself failed — nothing to upload; the worker retries. */
    data class ExportFailed(
        val cause: Throwable,
    ) : ScheduledBackupOutcome
}

/**
 * ADR-037: one automatic backup pass, kept free of WorkManager/Hilt so it runs against
 * fakes. Order is fixed and observable: (1) gate on the vault via [openVault] (the
 * ADR-043 `BackgroundSyncGate`: unlocked, or self-unlocked with the opt-in background
 * key; a locked vault is deferred WITHOUT any notification), (2) local export through
 * [BackupManager.exportLatestToAppStorage] — encrypted, pruned to the newest three,
 * exactly the file the manual export keeps —, (3) only when Drive backups are enabled
 * AND an account is linked, [DriveBackupService.uploadLatestBackup] (the same call the
 * manual export chains through `scheduleBackupUpload`).
 *
 * A transient upload failure (offline is the common case, because this job carries
 * no network constraint so the LOCAL backup still happens) is NOT retried here — a
 * retry would re-export for nothing. Instead [deferUpload] hands the sealed file to
 * the network-gated one-shot Drive backup worker, which has its own backoff.
 */
class ScheduledBackupRunner(
    /** True when the encrypted store can be read now (already unlocked or self-unlocked). */
    private val openVault: suspend () -> Boolean,
    private val backupManager: BackupManager,
    private val linkStore: GoogleLinkStore,
    private val driveBackupService: DriveBackupService,
    /** Queues the network-gated Drive backup upload for later. */
    private val deferUpload: () -> Unit,
) {
    suspend fun run(): ScheduledBackupOutcome {
        if (!openVault()) return ScheduledBackupOutcome.Locked
        try {
            backupManager.exportLatestToAppStorage()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return ScheduledBackupOutcome.ExportFailed(e)
        }
        val link = linkStore.current()
        if (!link.driveBackupEnabled || !link.isLinked) return ScheduledBackupOutcome.LocalOnly
        return when (val upload = driveBackupService.uploadLatestBackup()) {
            DriveBackupUploadResult.Uploaded -> ScheduledBackupOutcome.Uploaded
            DriveBackupUploadResult.Skipped -> ScheduledBackupOutcome.LocalOnly
            is DriveBackupUploadResult.Failed -> {
                // No token right now: nothing to defer, the next link pass covers it.
                if (upload.cause is GoogleNotAvailableException) return ScheduledBackupOutcome.LocalOnly
                deferUpload()
                ScheduledBackupOutcome.UploadDeferred(upload.cause)
            }
        }
    }
}
