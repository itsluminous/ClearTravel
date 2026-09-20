package com.itsluminous.cleartravel.feature.flights.polling

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.itsluminous.cleartravel.core.data.repository.FlightRepository
import com.itsluminous.cleartravel.core.notifications.FlightNotifier
import com.itsluminous.cleartravel.feature.flights.checkin.CheckInRuleSource
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import java.time.Duration
import java.time.Instant

/**
 * Background flight polling (ADR-010). Deliberately does NOT run a headless WebView
 * scrape — status refresh stays interactive; this worker computes what can be known
 * offline (check-in window crossings from the ADR-003 data file) and posts the
 * spec-sanctioned "status may have changed — tap to check" nudge near departure.
 *
 * Escalating cadence: each run reschedules itself as unique one-time work with
 * [NextPollDelay]'s soonest delay — a self-chaining chain, because WorkManager's
 * periodic API cannot vary its period. Dependencies come from an [EntryPoint]
 * (NOT @HiltWorker) so no app-module `Configuration.Provider` wiring is required.
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
    }

    override suspend fun doWork(): Result {
        val deps = EntryPointAccessors.fromApplication(applicationContext, Dependencies::class.java)
        val stateStore = PollStateStore(applicationContext)

        val flights = deps.flightRepository().observeActive().first()
        val plan =
            FlightPollEvaluator.evaluate(
                flights = flights,
                rules = deps.checkInRuleSource().load(),
                now = Instant.now(),
                alreadySent = stateStore.sentKeys(),
            )

        val notifier = deps.flightNotifier()
        for (notification in plan.notifications) {
            val flight = notification.flight
            val label = "${flight.airlineIata} ${flight.flightNumber}"
            when (notification) {
                is PollNotification.CheckInOpen -> notifier.notifyCheckInOpen(flight.id, label)
                is PollNotification.StatusCheckHint -> notifier.notifyStatusMayHaveChanged(flight.id, label)
            }
            stateStore.markSent(notification.dedupeKey)
        }

        plan.nextDelay?.let { delay -> FlightPollScheduler.schedule(applicationContext, delay) }
        return Result.success()
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
