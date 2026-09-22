package com.itsluminous.cleartravel.core.google.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker.Result
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.google.backup.ScheduledBackupOutcome
import com.itsluminous.cleartravel.core.model.BackupSchedule
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.TimeUnit

/** ADR-037: enum → periodic request mapping, OFF cancels, verdict mapping. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScheduledBackupSchedulerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        // The test scheduler runs the first period at once; substitute a no-op worker so
        // the enqueue/update/cancel bookkeeping is what's under test (the real worker
        // needs Hilt).
        val noOpFactory =
            object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): Worker =
                    object : Worker(appContext, workerParameters) {
                        override fun doWork(): Result = Result.success()
                    }
            }
        val config =
            Configuration
                .Builder()
                .setExecutor(SynchronousExecutor())
                .setWorkerFactory(noOpFactory)
                .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
    }

    @Test
    fun `off maps to no request, every cadence to its period without constraints`() {
        assertThat(WorkManagerScheduledBackupScheduler.buildRequest(BackupSchedule.OFF)).isNull()
        for (schedule in listOf(BackupSchedule.DAILY, BackupSchedule.WEEKLY, BackupSchedule.MONTHLY)) {
            val request = WorkManagerScheduledBackupScheduler.buildRequest(schedule)!!
            assertThat(request.workSpec.intervalDuration).isEqualTo(schedule.period!!.toMillis())
            assertThat(request.workSpec.isPeriodic).isTrue()
            assertThat(request.workSpec.constraints.requiredNetworkType).isEqualTo(NetworkType.NOT_REQUIRED)
            assertThat(request.workSpec.workerClassName).isEqualTo(ScheduledBackupWorker::class.java.name)
        }
    }

    @Test
    fun `applying a cadence enqueues the unique periodic job and off cancels it`() {
        val scheduler = WorkManagerScheduledBackupScheduler(context)
        val workManager = WorkManager.getInstance(context)

        scheduler.apply(BackupSchedule.DAILY)
        val enqueued = workManager.getWorkInfosForUniqueWork(WorkManagerScheduledBackupScheduler.UNIQUE_NAME).get(5, TimeUnit.SECONDS)
        assertThat(enqueued).hasSize(1)
        assertThat(enqueued.single().state).isEqualTo(WorkInfo.State.ENQUEUED)
        assertThat(enqueued.single().periodicityInfo?.repeatIntervalMillis).isEqualTo(TimeUnit.DAYS.toMillis(1))

        scheduler.apply(BackupSchedule.WEEKLY) // UPDATE: same unique job, new period
        val updated = workManager.getWorkInfosForUniqueWork(WorkManagerScheduledBackupScheduler.UNIQUE_NAME).get(5, TimeUnit.SECONDS)
        assertThat(updated).hasSize(1)
        assertThat(updated.single().id).isEqualTo(enqueued.single().id)
        assertThat(updated.single().periodicityInfo?.repeatIntervalMillis).isEqualTo(TimeUnit.DAYS.toMillis(7))

        scheduler.apply(BackupSchedule.OFF)
        val afterOff = workManager.getWorkInfosForUniqueWork(WorkManagerScheduledBackupScheduler.UNIQUE_NAME).get(5, TimeUnit.SECONDS)
        assertThat(afterOff.none { !it.state.isFinished }).isTrue()
    }

    @Test
    fun `only a failed export retries, capped at the attempt limit`() {
        val failed = ScheduledBackupOutcome.ExportFailed(IOException("disk"))
        assertThat(ScheduledBackupWorker.resolveVerdict(failed, runAttemptCount = 1)).isEqualTo(Result.retry())
        assertThat(ScheduledBackupWorker.resolveVerdict(failed, runAttemptCount = ScheduledBackupWorker.MAX_ATTEMPTS))
            .isEqualTo(Result.failure())

        assertThat(ScheduledBackupWorker.resolveVerdict(ScheduledBackupOutcome.Locked, 1)).isEqualTo(Result.success())
        assertThat(ScheduledBackupWorker.resolveVerdict(ScheduledBackupOutcome.LocalOnly, 1)).isEqualTo(Result.success())
        assertThat(ScheduledBackupWorker.resolveVerdict(ScheduledBackupOutcome.Uploaded, 1)).isEqualTo(Result.success())
        assertThat(ScheduledBackupWorker.resolveVerdict(ScheduledBackupOutcome.UploadDeferred(IOException("offline")), 1))
            .isEqualTo(Result.success())
    }
}
