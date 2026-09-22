package com.itsluminous.cleartravel.core.google.work

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.itsluminous.cleartravel.core.data.backup.BackupManager
import com.itsluminous.cleartravel.core.google.auth.GoogleLinkStore
import com.itsluminous.cleartravel.core.google.auth.GoogleSyncScheduler
import com.itsluminous.cleartravel.core.google.backup.DriveBackupService
import com.itsluminous.cleartravel.core.google.backup.ScheduledBackupOutcome
import com.itsluminous.cleartravel.core.google.backup.ScheduledBackupRunner
import com.itsluminous.cleartravel.core.model.BackupSchedule
import com.itsluminous.cleartravel.core.notifications.AppLockNotifier
import com.itsluminous.cleartravel.core.security.vault.KeyVault
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ClearTravelBackup"

/**
 * ADR-037: the periodic automatic backup. Plain (non-Hilt) worker resolved through an
 * entry point (the ADR-013 pattern of its siblings in this package); the actual pass
 * is [ScheduledBackupRunner]. Lives in `core:google` because the Drive leg needs
 * [DriveBackupService] and `core:google` already composes `core:data`'s
 * [BackupManager].
 *
 * ADR-031: needs the vault (the export reads Room). Before the first unlock of this
 * process it posts the "unlock to sync" nudge and succeeds quietly — no retry storm;
 * the next period (or the app-open re-affirm) tries again.
 */
class ScheduledBackupWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ScheduledBackupEntryPoint {
        fun keyVault(): KeyVault

        fun appLockNotifier(): AppLockNotifier

        fun backupManager(): BackupManager

        fun googleLinkStore(): GoogleLinkStore

        fun driveBackupService(): DriveBackupService

        fun googleSyncScheduler(): GoogleSyncScheduler
    }

    override suspend fun doWork(): Result {
        val deps = EntryPointAccessors.fromApplication(applicationContext, ScheduledBackupEntryPoint::class.java)
        val runner =
            ScheduledBackupRunner(
                isUnlocked = { deps.keyVault().isUnlocked },
                backupManager = deps.backupManager(),
                linkStore = deps.googleLinkStore(),
                driveBackupService = deps.driveBackupService(),
                notifyLocked = { deps.appLockNotifier().notifyUnlockToSync() },
                deferUpload = { deps.googleSyncScheduler().scheduleBackupUpload() },
            )
        val outcome = runner.run()
        if (outcome is ScheduledBackupOutcome.ExportFailed) Log.w(TAG, "automatic backup attempt $runAttemptCount failed", outcome.cause)
        return resolveVerdict(outcome, runAttemptCount)
    }

    companion object {
        const val MAX_ATTEMPTS = 5

        /**
         * Pure verdict mapping. Only a failed EXPORT retries (with WorkManager's default
         * exponential backoff, capped at [MAX_ATTEMPTS]); a deferred upload already
         * queued its own network-gated worker, and a locked vault must not retry.
         */
        internal fun resolveVerdict(
            outcome: ScheduledBackupOutcome,
            runAttemptCount: Int,
        ): Result =
            when (outcome) {
                is ScheduledBackupOutcome.ExportFailed ->
                    if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
                ScheduledBackupOutcome.Locked,
                ScheduledBackupOutcome.LocalOnly,
                ScheduledBackupOutcome.Uploaded,
                is ScheduledBackupOutcome.UploadDeferred,
                -> Result.success()
            }
    }
}

/** ADR-037: keeps WorkManager's unique periodic job in step with the [BackupSchedule] setting. */
interface ScheduledBackupScheduler {
    /**
     * [BackupSchedule.OFF] cancels the job; any cadence enqueues/updates it. Safe to
     * call repeatedly (the ViewModel on every change, the app shell on every open):
     * `UPDATE` keeps the pending next-run time, so re-affirming never resets the clock.
     */
    fun apply(schedule: BackupSchedule)
}

/** [ScheduledBackupScheduler] over WorkManager. */
@Singleton
class WorkManagerScheduledBackupScheduler
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : ScheduledBackupScheduler {
        override fun apply(schedule: BackupSchedule) {
            val workManager = WorkManager.getInstance(context)
            val request = buildRequest(schedule)
            if (request == null) {
                workManager.cancelUniqueWork(UNIQUE_NAME)
            } else {
                workManager.enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
            }
        }

        companion object {
            const val UNIQUE_NAME = "scheduled-backup"
            private const val BACKOFF_SECONDS = 30L

            /**
             * Pure request mapping: null for [BackupSchedule.OFF], otherwise a periodic
             * request with the cadence's period and NO constraints — the local backup must
             * run offline; the Drive leg handles connectivity itself (ADR-037).
             */
            internal fun buildRequest(schedule: BackupSchedule): PeriodicWorkRequest? {
                val period = schedule.period ?: return null
                return PeriodicWorkRequestBuilder<ScheduledBackupWorker>(period.toMillis(), TimeUnit.MILLISECONDS)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                    .addTag(UNIQUE_NAME)
                    .build()
            }
        }
    }
