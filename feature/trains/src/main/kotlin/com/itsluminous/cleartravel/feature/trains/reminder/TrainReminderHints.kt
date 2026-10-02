package com.itsluminous.cleartravel.feature.trains.reminder

import com.itsluminous.cleartravel.core.data.repository.TrainRepository
import com.itsluminous.cleartravel.core.data.sync.BackgroundSyncStateStore
import com.itsluminous.cleartravel.core.data.sync.PnrHash
import com.itsluminous.cleartravel.core.data.sync.TrainDepartureHint
import com.itsluminous.cleartravel.core.model.TrainTicket
import kotlinx.coroutines.flow.first
import java.time.ZoneId

/** One active ticket with the departure the reminder reasons about (ADR-044). */
data class ReminderCandidate(
    val ticket: TrainTicket,
    val departure: TrainDeparture,
) {
    val pnrHash: String get() = PnrHash.of(ticket.pnr)
    val hint: TrainDepartureHint get() = TrainDepartureHint(departure.instant, pnrHash)
}

/**
 * ADR-044 §4: the unlocked side of the plaintext train hints. Reads the active tickets
 * and their stored routes, derives each departure, and rewrites
 * `BackgroundSyncStateStore.trainDepartureHints` (+ prunes the reminded set to the
 * tickets that still exist) so a LOCKED worker run can tell whether a reminder is due
 * without the database. Called from `AppStartupTasks` (app open, every active-list
 * change) and from every granted worker run.
 */
object TrainReminderHints {
    /** Active tickets with a journey date, each with its derived departure. */
    suspend fun candidates(
        trainRepository: TrainRepository,
        zone: ZoneId,
    ): List<ReminderCandidate> =
        trainRepository
            .observeActive()
            .first()
            .mapNotNull { ticket ->
                val stops = trainRepository.observeRouteStops(ticket.id).first()
                TrainDeparture.of(ticket, stops, zone)?.let { ReminderCandidate(ticket, it) }
            }

    /** Writes the hints for [candidates] and drops reminded keys of tickets no longer active. */
    suspend fun publish(
        candidates: List<ReminderCandidate>,
        stateStore: BackgroundSyncStateStore,
    ) {
        stateStore.setTrainDepartureHints(candidates.map(ReminderCandidate::hint))
        stateStore.retainRemindedTrainKeys(candidates.flatMap { TrainReminderPolicy.allKeys(it.pnrHash) })
    }

    /** [candidates] + [publish] in one call. */
    suspend fun refresh(
        trainRepository: TrainRepository,
        stateStore: BackgroundSyncStateStore,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<ReminderCandidate> = candidates(trainRepository, zone).also { publish(it, stateStore) }
}
