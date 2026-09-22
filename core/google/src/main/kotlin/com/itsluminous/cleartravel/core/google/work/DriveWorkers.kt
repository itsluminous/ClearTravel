package com.itsluminous.cleartravel.core.google.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.itsluminous.cleartravel.core.google.auth.GoogleNotAvailableException
import com.itsluminous.cleartravel.core.google.backup.DriveBackupService
import com.itsluminous.cleartravel.core.google.backup.DriveBackupUploadResult
import com.itsluminous.cleartravel.core.google.drive.DriveUploadEngine
import com.itsluminous.cleartravel.core.google.drive.DriveUploadResult
import com.itsluminous.cleartravel.core.notifications.AppLockNotifier
import com.itsluminous.cleartravel.core.security.vault.KeyVault
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException

private const val TAG = "ClearTravelDrive"
private const val MAX_ATTEMPTS = 5

/**
 * Drains the Drive attachment/boarding-pass upload queue with retry/backoff. Plain
 * (non-Hilt) worker resolved through an entry point (the ADR-013 pattern).
 *
 * ADR-031: needs the vault (database + file keys). Before the first unlock of this
 * process it posts the "unlock to sync" nudge and succeeds quietly — the periodic
 * drain and the toggle/link passes pick the queue up later.
 */
class DriveUploadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface DriveUploadEntryPoint {
        fun driveUploadEngine(): DriveUploadEngine

        fun keyVault(): KeyVault

        fun appLockNotifier(): AppLockNotifier
    }

    override suspend fun doWork(): Result {
        val deps = EntryPointAccessors.fromApplication(applicationContext, DriveUploadEntryPoint::class.java)
        if (!deps.keyVault().isUnlocked) {
            deps.appLockNotifier().notifyUnlockToSync()
            return Result.success()
        }
        val engine = deps.driveUploadEngine()
        return try {
            resolveQueueResult(engine.processQueue(), runAttemptCount)
        } catch (e: CancellationException) {
            throw e
        } catch (e: GoogleNotAvailableException) {
            Result.success() // No link/token right now — the next toggle/link pass covers it.
        } catch (e: Exception) {
            Log.w(TAG, "drive upload attempt $runAttemptCount failed", e)
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    companion object {
        /** Pure verdict mapping — unit-tested queue retry/backoff semantics. */
        internal fun resolveQueueResult(
            result: DriveUploadResult,
            runAttemptCount: Int,
        ): Result =
            when {
                result is DriveUploadResult.Done && result.failed > 0 ->
                    if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
                else -> Result.success()
            }
    }
}

/**
 * Uploads the newest app-storage backup ZIP to Drive (after each successful export).
 * Runs even while the vault is locked: the file is already a sealed portable envelope
 * and the upload needs no key (ADR-031).
 */
class DriveBackupWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface DriveBackupEntryPoint {
        fun driveBackupService(): DriveBackupService
    }

    override suspend fun doWork(): Result {
        val service =
            EntryPointAccessors
                .fromApplication(applicationContext, DriveBackupEntryPoint::class.java)
                .driveBackupService()
        return try {
            when (val result = service.uploadLatestBackup()) {
                DriveBackupUploadResult.Skipped, DriveBackupUploadResult.Uploaded -> Result.success()
                is DriveBackupUploadResult.Failed -> {
                    if (result.cause is GoogleNotAvailableException) return Result.success()
                    Log.w(TAG, "backup upload attempt $runAttemptCount failed", result.cause)
                    if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: GoogleNotAvailableException) {
            Result.success()
        }
    }
}
