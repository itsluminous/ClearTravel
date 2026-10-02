package com.itsluminous.cleartravel.startup

import android.content.Context
import com.itsluminous.cleartravel.core.data.repository.FlightRepository
import com.itsluminous.cleartravel.core.data.repository.SettingsRepository
import com.itsluminous.cleartravel.core.data.repository.TrainRepository
import com.itsluminous.cleartravel.core.data.sync.BackgroundSyncStateStore
import com.itsluminous.cleartravel.core.google.work.ScheduledBackupScheduler
import com.itsluminous.cleartravel.feature.flights.polling.FlightPollScheduler
import com.itsluminous.cleartravel.feature.trains.isPastJourney
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One-shot housekeeping run from `MainActivity.onCreate` off the UI thread:
 *
 * 1. **Auto-archive past journeys** — train tickets whose journey date is strictly
 *    before today (`feature:trains`' `isPastJourney`) and flights whose day is past
 *    ([isPastFlight]) move to the archive. Idempotent: archived rows leave
 *    `observeActive` and are never re-processed.
 * 2. **Flight poll kick** — restarts the ADR-013 self-chaining WorkManager chain for
 *    the existing future flights. Needed at process start because the chain's links
 *    live only in WorkManager: a reboot/force-stop (or a flight saved without the
 *    Flights tab ever being opened) would otherwise leave polling dormant. Uses the
 *    documented feature-owned scheduler (`FlightPollScheduler.ensureScheduled`,
 *    KEEP policy), so an already-pending run is never reset.
 * 3. **Automatic-backup re-affirm** (ADR-037) — re-applies the persisted
 *    `BackupSchedule` to WorkManager's unique periodic job (`UPDATE` keeps the pending
 *    next-run time; `OFF` cancels), for the same reason as the poll kick: the job
 *    lives only in WorkManager.
 * 4. **Sync bookkeeping** (ADR-043) — the user just unlocked, so every "skipped while
 *    locked (N times since last unlock)" counter resets, and the plaintext flight
 *    departure hints are rewritten from the live flight list (and kept fresh by
 *    [keepFlightDepartureHintsFresh] for as long as the activity lives) so a locked
 *    poll run can tell whether a flight is imminent without the database.
 */
@Singleton
class AppStartupTasks
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val trainRepository: TrainRepository,
        private val flightRepository: FlightRepository,
        private val settingsRepository: SettingsRepository,
        private val scheduledBackupScheduler: ScheduledBackupScheduler,
        private val backgroundSyncStateStore: BackgroundSyncStateStore,
    ) {
        suspend fun runOnAppOpen() =
            withContext(Dispatchers.IO) {
                archivePastJourneys(LocalDate.now(ZoneId.systemDefault()))
                backgroundSyncStateStore.resetDeferredCounts()
                kickFlightPolling()
                reaffirmBackupSchedule()
            }

        /**
         * ADR-043: never returns — collects the active flights for the caller's scope and
         * rewrites the departure hints on every change (add, edit, archive, import,
         * restore). Call from a lifecycle scope once the gate is open.
         */
        suspend fun keepFlightDepartureHintsFresh() =
            withContext(Dispatchers.IO) {
                flightRepository
                    .observeActive()
                    .map { flights -> flights.mapNotNull { it.schedDep }.toSet() }
                    .collect { departures -> backgroundSyncStateStore.setFlightDepartureHints(departures) }
            }

        private suspend fun reaffirmBackupSchedule() {
            scheduledBackupScheduler.apply(settingsRepository.backupSchedule.first())
        }

        private suspend fun archivePastJourneys(today: LocalDate) {
            trainRepository
                .observeActive()
                .first()
                .filter { isPastJourney(it, today) }
                .forEach { trainRepository.setArchived(it.id, archived = true) }
            flightRepository
                .observeActive()
                .first()
                .filter { isPastFlight(it, today) }
                .forEach { flightRepository.setArchived(it.id, archived = true) }
        }

        private suspend fun kickFlightPolling() {
            val departures = flightRepository.observeActive().first().mapNotNull { it.schedDep }
            backgroundSyncStateStore.setFlightDepartureHints(departures)
            FlightPollScheduler.ensureScheduled(context, departures.minOrNull())
        }
    }
