package com.itsluminous.cleartravel.core.google.backup

import com.itsluminous.cleartravel.core.data.repository.ChecklistRepository
import com.itsluminous.cleartravel.core.data.repository.FlightRepository
import com.itsluminous.cleartravel.core.data.repository.TrainRepository
import com.itsluminous.cleartravel.core.data.repository.TripRepository
import kotlinx.coroutines.flow.first

/**
 * The fresh-install heuristic (spec feature 6): the local database counts as
 * "fresh-ish" when it holds ZERO trips, journeys (train or flight, archived
 * included) and checklists — only then is the restore-from-Drive prompt offered,
 * so an established device is never nagged to import over its data.
 */
interface FreshInstallDetector {
    suspend fun isFreshInstall(): Boolean
}

/** [FreshInstallDetector] over the Room-backed repositories. */
class RepositoryFreshInstallDetector(
    private val tripRepository: TripRepository,
    private val trainRepository: TrainRepository,
    private val flightRepository: FlightRepository,
    private val checklistRepository: ChecklistRepository,
) : FreshInstallDetector {
    override suspend fun isFreshInstall(): Boolean =
        tripRepository.observeActive().first().isEmpty() &&
            tripRepository.observeArchived().first().isEmpty() &&
            trainRepository.observeActive().first().isEmpty() &&
            trainRepository.observeArchived().first().isEmpty() &&
            flightRepository.observeActive().first().isEmpty() &&
            flightRepository.observeArchived().first().isEmpty() &&
            checklistRepository.observeChecklists().first().isEmpty()
}
