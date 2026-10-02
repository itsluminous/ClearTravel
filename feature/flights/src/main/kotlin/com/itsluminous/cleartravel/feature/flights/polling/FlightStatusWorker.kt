package com.itsluminous.cleartravel.feature.flights.polling

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.itsluminous.cleartravel.core.data.repository.FlightRepository
import com.itsluminous.cleartravel.core.data.sync.BackgroundSyncGate
import com.itsluminous.cleartravel.core.data.sync.BackgroundSyncStateStore
import com.itsluminous.cleartravel.core.notifications.AppLockNotifier
import com.itsluminous.cleartravel.core.notifications.FlightNotifier
import com.itsluminous.cleartravel.feature.flights.checkin.CheckInRuleSource
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.time.Duration
import java.time.Instant

/**
 * Background flight polling (ADR-013). Deliberately does NOT run a headless WebView
 * scrape — status refresh stays interactive; this worker computes what can be known
 * offline (check-in window crossings from the ADR-003 data file) and posts the
 * spec-sanctioned "status may have changed — tap to check" nudge near departure.
 *
 * Escalating cadence: each run reschedules itself as unique one-time work with
 * [NextPollDelay]'s soonest delay — a self-chaining chain, because WorkManager's
 * periodic API cannot vary its period. Dependencies come from an [EntryPoint]
 * (NOT @HiltWorker). Kept that way on purpose after the app wiring existed (cleanup
 * assessment 2026-09-22): all five workers in the app (this one, `CalendarSyncWorker`,
 * `DriveUploadWorker`, `DriveBackupWorker`, the calendar cleanup) share the pattern,
 * `@HiltWorker` would need a `Configuration.Provider` Application + the default
 * `WorkManagerInitializer` removed from the manifest + `hilt-work` compilers in two more
 * modules + a test `Configuration` for the hermetic e2e `HiltTestApplication`, and the
 * only thing gained is constructor injection of the same five dependencies. Not worth
 * the surface; the EntryPoint keeps the worker a plain WorkManager class.
 *
 * ADR-031/043: the flight list lives in the encrypted database, whose key exists only
 * after an unlock in this process — or, when the user opted into "allow sync while
 * locked", after the [BackgroundSyncGate] unwrapped it with the background key. A run
 * that finds the vault locked ([FlightPollOutcome.Deferred]) posts the "unlock to sync"
 * nudge ONLY when [UnlockNudgePolicy] says an imminent flight makes it worth it (once
 * per process), then ends WITHOUT re-chaining when the opt-in is off (`AppStartupTasks`
 * re-kicks the chain on the next unlocked app open) and with `Result.retry()` when it is
 * on (the device was merely screen-locked; WorkManager's backoff tries again). The
 * actual pass is [FlightPollRunner].
 */
class FlightStatusWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun flightRepository(): FlightRepository

        fun checkInRuleSource(): CheckInRuleSource

        fun flightNotifier(): FlightNotifier

        fun appLockNotifier(): AppLockNotifier

        fun backgroundSyncGate(): BackgroundSyncGate

        fun backgroundSyncStateStore(): BackgroundSyncStateStore
    }

    override suspend fun doWork(): Result {
        val deps = EntryPointAccessors.fromApplication(applicationContext, Dependencies::class.java)
        val pollState = PollStateStore(applicationContext)
        val notifier = deps.flightNotifier()
        val lockNotifier = deps.appLockNotifier()
        val runner =
            FlightPollRunner(
                gate = deps.backgroundSyncGate(),
                stateStore = deps.backgroundSyncStateStore(),
                flightRepository = deps.flightRepository(),
                checkInRuleSource = deps.checkInRuleSource(),
                sentKeys = pollState::sentKeys,
                markSent = pollState::markSent,
                post = { notification ->
                    val flight = notification.flight
                    val label = "${flight.airlineIata} ${flight.flightNumber}"
                    when (notification) {
                        is PollNotification.CheckInOpen -> notifier.notifyCheckInOpen(flight.id, label)
                        is PollNotification.StatusCheckHint -> notifier.notifyStatusMayHaveChanged(flight.id, label)
                    }
                },
                notifyUnlockToSync = lockNotifier::notifyUnlockToSync,
                nudgeAlreadyPosted = { lockNotifier.postedThisProcess },
            )
        val outcome = runner.run()
        Log.i(TAG, describe(outcome))
        if (outcome is FlightPollOutcome.Ran) {
            outcome.nextDelay?.let { delay -> FlightPollScheduler.schedule(applicationContext, delay) }
        }
        return resolveVerdict(outcome)
    }

    companion object {
        private const val TAG = "ClearTravelFlightPoll"

        /**
         * Pure verdict mapping. A deferred run with the opt-in key on retries (the device
         * was screen-locked — WorkManager's backoff tries again soon); without it, success
         * and NO re-chain: nothing changes until the user opens the app, which re-kicks.
         */
        internal fun resolveVerdict(outcome: FlightPollOutcome): Result =
            when (outcome) {
                is FlightPollOutcome.Deferred -> if (outcome.backgroundKeyEnabled) Result.retry() else Result.success()
                is FlightPollOutcome.Ran -> Result.success()
            }

        /** One logcat line per run — the on-device proof of ADR-043 reads these. */
        internal fun describe(outcome: FlightPollOutcome): String =
            when (outcome) {
                is FlightPollOutcome.Deferred ->
                    "poll deferred: vault locked, nudged=${outcome.nudged}, backgroundKey=${outcome.backgroundKeyEnabled}"
                is FlightPollOutcome.Ran ->
                    "poll ran: posted=${outcome.posted}, nextDelay=${outcome.nextDelay}, backgroundKeyUnlocked=${outcome.viaBackgroundKey}"
            }
    }
}

/** Schedules/reschedules the unique poll chain. Feature-local — no app wiring needed. */
object FlightPollScheduler {
    const val UNIQUE_WORK_NAME = "flights-status-poll"

    /** Enqueues the next poll [delay] from now, replacing any pending run. */
    fun schedule(
        context: Context,
        delay: Duration,
    ) {
        val request =
            OneTimeWorkRequestBuilder<FlightStatusWorker>()
                .setInitialDelay(delay.coerceAtLeast(NextPollDelay.MIN_PERIOD))
                .build()
        WorkManager
            .getInstance(context)
            .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    /**
     * Ensures a chain exists without resetting an already-pending run (called from
     * the flights UI whenever the list of flights changes).
     */
    fun ensureScheduled(
        context: Context,
        nextDeparture: Instant?,
    ) {
        val delay = NextPollDelay.compute(Instant.now(), nextDeparture) ?: return
        val request =
            OneTimeWorkRequestBuilder<FlightStatusWorker>()
                .setInitialDelay(delay.coerceAtLeast(NextPollDelay.MIN_PERIOD))
                .build()
        WorkManager
            .getInstance(context)
            .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    private fun Duration.coerceAtLeast(minimum: Duration): Duration = if (this < minimum) minimum else this
}

/**
 * Which poll notifications were already sent (dedupe across runs), persisted in a
 * feature-local SharedPreferences — deliberately NOT a Room column: notification
 * bookkeeping is device-local state and must never enter the backup/merge surface
 * (ADR-002 applies to synced entities only).
 */
class PollStateStore(
    context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun sentKeys(): Set<String> = prefs.getStringSet(KEY_SENT, emptySet()).orEmpty()

    fun markSent(key: String) {
        prefs.edit().putStringSet(KEY_SENT, sentKeys() + key).apply()
    }

    private companion object {
        const val PREFS_NAME = "flights_poll_state"
        const val KEY_SENT = "sent_keys"
    }
}
