package com.itsluminous.cleartravel.core.google.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.itsluminous.cleartravel.core.google.auth.GoogleSyncScheduler
import com.itsluminous.cleartravel.core.google.calendar.CalendarSyncWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WorkManager wiring of every Google job (spec feature 5: all Google work runs in
 * background jobs after the local write commits — never on the UI path). Each
 * feature gets a one-shot "now" pass plus a 6-hourly periodic catch-up, all gated
 * on network; failures retry with exponential backoff inside the workers.
 */
@Singleton
class WorkManagerGoogleSyncScheduler
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : GoogleSyncScheduler {
        private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        private val workManager get() = WorkManager.getInstance(context)

        override fun scheduleCalendarSync() {
            workManager.enqueueUniqueWork(
                CALENDAR_SYNC_NOW,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<CalendarSyncWorker>()
                    .setConstraints(constraints)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                    .build(),
            )
            workManager.enqueueUniquePeriodicWork(
                CALENDAR_SYNC_PERIODIC,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<CalendarSyncWorker>(PERIODIC_HOURS, TimeUnit.HOURS)
                    .setConstraints(constraints)
                    .build(),
            )
        }

        override fun cancelCalendarSync() {
            workManager.cancelUniqueWork(CALENDAR_SYNC_NOW)
            workManager.cancelUniqueWork(CALENDAR_SYNC_PERIODIC)
        }

        override fun scheduleDriveUploads() {
            workManager.enqueueUniqueWork(
                DRIVE_UPLOAD_NOW,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<DriveUploadWorker>()
                    .setConstraints(constraints)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                    .build(),
            )
            workManager.enqueueUniquePeriodicWork(
                DRIVE_UPLOAD_PERIODIC,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<DriveUploadWorker>(PERIODIC_HOURS, TimeUnit.HOURS)
                    .setConstraints(constraints)
                    .build(),
            )
        }

        override fun cancelDriveUploads() {
            workManager.cancelUniqueWork(DRIVE_UPLOAD_NOW)
            workManager.cancelUniqueWork(DRIVE_UPLOAD_PERIODIC)
        }

        override fun scheduleBackupUpload() {
            workManager.enqueueUniqueWork(
                BACKUP_UPLOAD_NOW,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<DriveBackupWorker>()
                    .setConstraints(constraints)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                    .build(),
            )
        }

        override fun scheduleDisconnectCleanup(calendarId: String) {
            workManager.enqueueUniqueWork(
                CALENDAR_CLEANUP,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<CalendarSyncWorker>()
                    .setConstraints(constraints)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                    .setInputData(
                        workDataOf(
                            CalendarSyncWorker.KEY_ACTION to CalendarSyncWorker.ACTION_DELETE_CALENDAR,
                            CalendarSyncWorker.KEY_CALENDAR_ID to calendarId,
                        ),
                    ).build(),
            )
        }

        private companion object {
            const val CALENDAR_SYNC_NOW = "google-calendar-sync-now"
            const val CALENDAR_SYNC_PERIODIC = "google-calendar-sync-periodic"
            const val CALENDAR_CLEANUP = "google-calendar-cleanup"
            const val DRIVE_UPLOAD_NOW = "google-drive-upload-now"
            const val DRIVE_UPLOAD_PERIODIC = "google-drive-upload-periodic"
            const val BACKUP_UPLOAD_NOW = "google-drive-backup-upload"
            const val PERIODIC_HOURS = 6L
            const val BACKOFF_SECONDS = 30L
        }
    }
