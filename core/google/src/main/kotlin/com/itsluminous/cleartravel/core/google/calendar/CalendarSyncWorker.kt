package com.itsluminous.cleartravel.core.google.calendar

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.itsluminous.cleartravel.core.google.auth.GoogleNotAvailableException
import com.itsluminous.cleartravel.core.notifications.AppLockNotifier
import com.itsluminous.cleartravel.core.security.vault.KeyVault
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException

/**
 * WorkManager job driving the one-way calendar reconciliation. Plain (non-Hilt)
 * worker resolved through an entry point (the ADR-013 pattern) so no custom
 * `Configuration.Provider` is needed in :app.
 *
 * ADR-031: reconciliation reads Room, so before the first unlock of this process the
 * worker posts the "unlock to sync" nudge and succeeds quietly (the 6-hourly
 * periodic pass and the app-open re-kick cover it). The calendar-delete action needs
 * no database and runs regardless.
 */
class CalendarSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface CalendarSyncEntryPoint {
        fun calendarSyncEngine(): CalendarSyncEngine

        fun keyVault(): KeyVault

        fun appLockNotifier(): AppLockNotifier
    }

    override suspend fun doWork(): Result {
        val deps = EntryPointAccessors.fromApplication(applicationContext, CalendarSyncEntryPoint::class.java)
        val engine = deps.calendarSyncEngine()
        val result =
            when (inputData.getString(KEY_ACTION)) {
                ACTION_DELETE_CALENDAR -> {
                    val calendarId = inputData.getString(KEY_CALENDAR_ID) ?: return Result.failure()
                    engine.deleteCalendar(calendarId)
                }
                else -> {
                    if (!deps.keyVault().isUnlocked) {
                        deps.appLockNotifier().notifyUnlockToSync()
                        return Result.success()
                    }
                    engine.reconcile()
                }
            }
        return result.fold(
            onSuccess = { Result.success() },
            onFailure = { resolveFailure(it, runAttemptCount) },
        )
    }

    companion object {
        const val KEY_ACTION = "action"
        const val KEY_CALENDAR_ID = "calendar_id"
        const val ACTION_SYNC = "sync"
        const val ACTION_DELETE_CALENDAR = "delete_calendar"
        const val MAX_ATTEMPTS = 5
        private const val TAG = "ClearTravelGcal"

        /**
         * Maps an engine failure to the worker verdict (pure — unit-tested).
         * [GoogleNotAvailableException] (no link / no silent token right now) is a
         * PERMANENT local state, not a transient fault — skip quietly as success; the
         * next toggle/link/periodic pass covers it. A [CancellationException] is
         * rethrown (a cancelled worker is not a failed pass). Anything else (network,
         * HTTP 401/5xx) retries with backoff up to [MAX_ATTEMPTS].
         */
        internal fun resolveFailure(
            error: Throwable,
            runAttemptCount: Int,
        ): Result {
            if (error is CancellationException) throw error
            if (error is GoogleNotAvailableException) {
                Log.i(TAG, "calendar sync skipped: ${error.message}")
                return Result.success()
            }
            Log.w(TAG, "calendar sync attempt $runAttemptCount failed", error)
            return if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }
}
