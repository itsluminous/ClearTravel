package com.itsluminous.cleartravel.feature.flights.detail

import androidx.lifecycle.ViewModel
import com.itsluminous.cleartravel.core.data.repository.ItineraryRepository
import com.itsluminous.cleartravel.core.data.repository.TripRepository
import com.itsluminous.cleartravel.core.model.ItineraryItem
import com.itsluminous.cleartravel.core.model.Trip
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject

/**
 * One itinerary leg this flight is part of (ADR-028): the trip to open plus the day
 * it sits on. [dayIndex] is 0-based like the itinerary ("Day 1" = 0).
 */
data class LinkedTrip(
    val tripId: String,
    val tripName: String,
    val dayIndex: Int,
)

/**
 * "Part of" rows for the flight detail sheet (ADR-028): the reverse lookup of
 * itinerary legs linked to a flight, joined with their live trips. Read-only.
 */
@HiltViewModel
class FlightTripLinksViewModel
    @Inject
    constructor(
        private val itineraryRepository: ItineraryRepository,
        private val tripRepository: TripRepository,
    ) : ViewModel() {
        /** Linking legs in (day, order) sequence; legs of deleted/missing trips drop out. */
        @OptIn(ExperimentalCoroutinesApi::class)
        fun observeLinkedTrips(flightId: String): Flow<List<LinkedTrip>> =
            itineraryRepository.observeItemsLinkedToJourney(flightId).flatMapLatest { items ->
                val tripIds = items.map(ItineraryItem::tripId).distinct()
                if (tripIds.isEmpty()) {
                    flowOf(emptyList())
                } else {
                    combine(tripIds.map(tripRepository::observeTrip)) { trips ->
                        val byId = trips.filterNotNull().associateBy(Trip::id)
                        items.mapNotNull { item ->
                            byId[item.tripId]?.let { trip -> LinkedTrip(trip.id, trip.name, item.dayIndex) }
                        }
                    }
                }
            }
    }
