package com.itsluminous.cleartravel.feature.trains.reminder

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.itsluminous.cleartravel.core.data.repository.SettingsRepository
import com.itsluminous.cleartravel.core.data.repository.TrainRepository
import com.itsluminous.cleartravel.core.data.sync.BackgroundSyncGate
import com.itsluminous.cleartravel.core.data.sync.BackgroundSyncStateStore
import com.itsluminous.cleartravel.core.model.TrainReminderLead
import com.itsluminous.cleartravel.core.notifications.AppLockNotifier
import com.itsluminous.cleartravel.core.notifications.TrainNotifier
import com.itsluminous.cleartravel.feature.trains.R
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.time.Duration

/**
 * The periodic train journey reminder (ADR-044). Deliberately does NOT refresh the PNR
 * — that needs the user's captcha in the foreground `PnrCheck` WebView (ADR-011); this
 * worker only decides, from the stored journey date + route, that it is time to remind,
 * and the notification's tap lands in that check.
 *
 * Dependencies come from an [EntryPoint] (NOT `@HiltWorker`) for the reasons recorded on
 * `FlightStatusWorker` (ADR-013). ADR-043 applies: the ticket list is encrypted, so the
 * pass goes through [BackgroundSyncGate]; a locked run may post the shared "unlock to
 * sync" nudge — through the SAME once-per-process `AppLockNotifier` latch as the flight
 * poller — but only when a reminder is actually due per the plaintext hints. The actual
 * pass is [TrainReminderRunner].
 */
class TrainReminderWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun trainRepository(): TrainRepository

        fun settingsRepository(): SettingsRepository

        fun trainNotifier(): TrainNotifier

        fun appLockNotifier(): AppLockNotifier

        fun backgroundSyncGate(): BackgroundSyncGate

        fun backgroundSyncStateStore(): BackgroundSyncStateStore
    }

    override suspend fun doWork(): Result {
        val deps = EntryPointAccessors.fromApplication(applicationContext, Dependencies::class.java)
        val notifier = deps.trainNotifier()
        val lockNotifier = deps.appLockNotifier()
        val runner =
            TrainReminderRunner(
                gate = deps.backgroundSyncGate(),
                stateStore = deps.backgroundSyncStateStore(),
                trainRepository = deps.trainRepository(),
                settingsRepository = deps.settingsRepository(),
                post = notifier::notifyJourneyReminder,
                notifyUnlockToSync = lockNotifier::notifyUnlockToSync,
                nudgeAlreadyPosted = { lockNotifier.postedThisProcess },
                fallbackLabel = { pnr -> applicationContext.getString(R.string.trains_reminder_label_pnr, pnr) },
            )
        val outcome = runner.run()
        Log.i(TAG, describe(outcome))
        return resolveVerdict(outcome)
    }

    companion object {
        private const val TAG = "ClearTravelTrainReminder"

        /** Same mapping as the flight poller: opt-in key unusable → retry (device screen-locked); else success. */
        internal fun resolveVerdict(outcome: TrainReminderOutcome): Result =
            when (outcome) {
                is TrainReminderOutcome.Deferred -> if (outcome.backgroundKeyEnabled) Result.retry() else Result.success()
                is TrainReminderOutcome.Ran -> Result.success()
            }

        /** One logcat line per run — the on-device proof reads these. */
        internal fun describe(outcome: TrainReminderOutcome): String =
            when (outcome) {
                is TrainReminderOutcome.Deferred ->
                    "reminder deferred: vault locked, nudged=${outcome.nudged}, backgroundKey=${outcome.backgroundKeyEnabled}"
                is TrainReminderOutcome.Ran ->
                    "reminder ran: posted=${outcome.posted}, lead=${outcome.lead}, backgroundKeyUnlocked=${outcome.viaBackgroundKey}"
            }
    }
}

/**
 * Keeps the unique periodic reminder job in step with the setting (ADR-044 §3): any
 * lead → a 3-hourly `PeriodicWorkRequest` (KEEP — an already-pending run is never
 * reset), OFF → cancelled. Feature-local; the app shell calls [apply] on app open and
 * on every lead change.
 */
object TrainReminderScheduler {
    const val UNIQUE_WORK_NAME = "trains-journey-reminder"

    /**
     * Coarse on purpose: the lead is hours to days, and the policy's window is open until
     * departure. No initial delay — the first run may fire as soon as the job is enqueued
     * (a ticket added inside its window is reminded about at once), and WorkManager
     * refuses a forced run ahead of the schedule, which the device validation relies on.
     */
    val PERIOD: Duration = Duration.ofHours(3)

    fun apply(
        context: Context,
        lead: TrainReminderLead,
    ) {
        val workManager = WorkManager.getInstance(context)
        if (lead.lead == null) {
            workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<TrainReminderWorker>(PERIOD).build()
        workManager.enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}
