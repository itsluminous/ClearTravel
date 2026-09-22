package com.itsluminous.cleartravel.core.data.repository

import com.itsluminous.cleartravel.core.model.ItineraryItem
import com.itsluminous.cleartravel.core.model.Trip
import kotlinx.coroutines.flow.Flow

/**
 * Trips aggregate (ADR-004). Offline-first: all reads observe Room; every write bumps
 * `updatedAt`; deletes are soft (tombstones) and cascade to the trip's itinerary
 * items and trip-scoped checklists.
 */
interface TripRepository {
    fun observeActive(): Flow<List<Trip>>

    fun observeArchived(): Flow<List<Trip>>

    fun observeTrip(id: String): Flow<Trip?>

    suspend fun getTrip(id: String): Trip?

    /** Upserts [trip] with a bumped `updatedAt`; returns the stored copy. */
    suspend fun save(trip: Trip): Trip

    suspend fun setArchived(
        id: String,
        archived: Boolean,
    )

    /** Soft-deletes the trip AND its itinerary items + trip-scoped checklists. */
    suspend fun delete(id: String)
}

/** Itinerary items of a trip (ADR-004). */
interface ItineraryRepository {
    /** Live items ordered by (dayIndex, orderInDay). */
    fun observeItemsForTrip(tripId: String): Flow<List<ItineraryItem>>

    /**
     * Reverse lookup (ADR-028): live commute legs — across ALL trips — whose
     * `linkedJourneyId` is [journeyId], ordered by (dayIndex, orderInDay). Lets a
     * journey's detail sheet show which trips it is part of; resolve trip names via
     * [TripRepository.observeTrip].
     */
    fun observeItemsLinkedToJourney(journeyId: String): Flow<List<ItineraryItem>>

    suspend fun getItem(id: String): ItineraryItem?

    /** Upserts [item] with a bumped `updatedAt`; returns the stored copy. */
    suspend fun save(item: ItineraryItem): ItineraryItem

    /** Bulk upsert (reorder / day moves); all rows get the same bumped `updatedAt`. */
    suspend fun saveAll(items: List<ItineraryItem>): List<ItineraryItem>

    suspend fun delete(id: String)
}
