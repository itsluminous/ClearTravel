package com.itsluminous.cleartravel.startup

import android.content.Context
import com.itsluminous.cleartravel.core.data.repository.FlightRepository
import com.itsluminous.cleartravel.core.data.repository.TrainRepository
import com.itsluminous.cleartravel.feature.flights.polling.FlightPollScheduler
import com.itsluminous.cleartravel.feature.trains.isPastJourney
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
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
 */
@Singleton
class AppStartupTasks
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val trainRepository: TrainRepository,
        private val flightRepository: FlightRepository,
    ) {
        suspend fun runOnAppOpen() =
            withContext(Dispatchers.IO) {
                archivePastJourneys(LocalDate.now(ZoneId.systemDefault()))
                kickFlightPolling()
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
            val nextDeparture =
                flightRepository
                    .observeActive()
                    .first()
                    .mapNotNull { it.schedDep }
                    .minOrNull()
            FlightPollScheduler.ensureScheduled(context, nextDeparture)
        }
    }
