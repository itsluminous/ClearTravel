package com.itsluminous.cleartravel.core.google.work

import androidx.work.ListenableWorker.Result
import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.google.auth.GoogleNotAvailableException
import com.itsluminous.cleartravel.core.google.calendar.CalendarSyncWorker
import com.itsluminous.cleartravel.core.google.drive.DriveUploadResult
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/** Worker verdict mapping — the queue retry/backoff semantics, pure and offline. */
@RunWith(RobolectricTestRunner::class)
class WorkerVerdictsTest {
    @Test
    fun `calendar sync retries transient failures up to the cap`() {
        assertThat(CalendarSyncWorker.resolveFailure(IOException("http 500"), runAttemptCount = 1))
            .isEqualTo(Result.retry())
        assertThat(CalendarSyncWorker.resolveFailure(IOException("http 500"), runAttemptCount = CalendarSyncWorker.MAX_ATTEMPTS))
            .isEqualTo(Result.failure())
    }

    @Test
    fun `calendar sync treats a missing google link as quiet success, not retry noise`() {
        assertThat(CalendarSyncWorker.resolveFailure(GoogleNotAvailableException(), runAttemptCount = 1))
            .isEqualTo(Result.success())
    }

    @Test
    fun `drive upload queue with failures retries with backoff, clean pass succeeds`() {
        assertThat(DriveUploadWorker.resolveQueueResult(DriveUploadResult.Done(uploaded = 2, failed = 1), runAttemptCount = 1))
            .isEqualTo(Result.retry())
        assertThat(DriveUploadWorker.resolveQueueResult(DriveUploadResult.Done(uploaded = 3, failed = 0), runAttemptCount = 1))
            .isEqualTo(Result.success())
        assertThat(DriveUploadWorker.resolveQueueResult(DriveUploadResult.Skipped, runAttemptCount = 1))
            .isEqualTo(Result.success())
        assertThat(DriveUploadWorker.resolveQueueResult(DriveUploadResult.Done(uploaded = 0, failed = 1), runAttemptCount = 5))
            .isEqualTo(Result.failure())
    }
}
