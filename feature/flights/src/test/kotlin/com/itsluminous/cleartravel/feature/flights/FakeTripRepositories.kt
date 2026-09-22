package com.itsluminous.cleartravel.feature.flights

import com.itsluminous.cleartravel.core.data.repository.ItineraryRepository
import com.itsluminous.cleartravel.core.data.repository.TripRepository
import com.itsluminous.cleartravel.core.model.ItineraryItem
import com.itsluminous.cleartravel.core.model.Trip
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** Read-only [TripRepository] fake — only the "Part of" lookup paths (ADR-028) are exercised. */
class FakeTripRepository : TripRepository {
    val trips = MutableStateFlow<List<Trip>>(emptyList())

    override fun observeActive(): Flow<List<Trip>> = trips.map { list -> list.filter { !it.archived } }

    override fun observeArchived(): Flow<List<Trip>> = trips.map { list -> list.filter { it.archived } }

    override fun observeTrip(id: String): Flow<Trip?> = trips.map { list -> list.firstOrNull { it.id == id } }

    override suspend fun getTrip(id: String): Trip? = trips.value.firstOrNull { it.id == id }

    override suspend fun save(trip: Trip): Trip {
        trips.value = trips.value.filterNot { it.id == trip.id } + trip
        return trip
    }

    override suspend fun setArchived(
        id: String,
        archived: Boolean,
    ) = Unit

    override suspend fun delete(id: String) {
        trips.value = trips.value.filterNot { it.id == id }
    }
}

/** Read-only [ItineraryRepository] fake ordered like the real DAO (day, orderInDay). */
class FakeItineraryRepository : ItineraryRepository {
    val items = MutableStateFlow<List<ItineraryItem>>(emptyList())

    override fun observeItemsForTrip(tripId: String): Flow<List<ItineraryItem>> =
        items.map { list -> list.filter { it.tripId == tripId }.sortedWith(compareBy({ it.dayIndex }, { it.orderInDay })) }

    override fun observeItemsLinkedToJourney(journeyId: String): Flow<List<ItineraryItem>> =
        items.map { list ->
            list.filter { it.linkedJourneyId == journeyId }.sortedWith(compareBy({ it.dayIndex }, { it.orderInDay }))
        }

    override suspend fun getItem(id: String): ItineraryItem? = items.value.firstOrNull { it.id == id }

    override suspend fun save(item: ItineraryItem): ItineraryItem {
        items.value = items.value.filterNot { it.id == item.id } + item
        return item
    }

    override suspend fun saveAll(items: List<ItineraryItem>): List<ItineraryItem> {
        items.forEach { save(it) }
        return items
    }

    override suspend fun delete(id: String) {
        items.value = items.value.filterNot { it.id == id }
    }
}
