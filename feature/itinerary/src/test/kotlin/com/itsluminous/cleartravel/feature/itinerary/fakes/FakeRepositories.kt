package com.itsluminous.cleartravel.feature.itinerary.fakes

import com.itsluminous.cleartravel.core.data.provider.FlightStatusResult
import com.itsluminous.cleartravel.core.data.provider.TrainStatusResult
import com.itsluminous.cleartravel.core.data.repository.FlightRepository
import com.itsluminous.cleartravel.core.data.repository.ItineraryRepository
import com.itsluminous.cleartravel.core.data.repository.TrainRepository
import com.itsluminous.cleartravel.core.data.repository.TripRepository
import com.itsluminous.cleartravel.core.model.FlightJourney
import com.itsluminous.cleartravel.core.model.ItineraryItem
import com.itsluminous.cleartravel.core.model.TrainCoach
import com.itsluminous.cleartravel.core.model.TrainPassenger
import com.itsluminous.cleartravel.core.model.TrainRouteStop
import com.itsluminous.cleartravel.core.model.TrainTicket
import com.itsluminous.cleartravel.core.model.Trip
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** In-memory [TripRepository] fake: a single backing StateFlow, hard deletes. */
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
    ) {
        trips.value = trips.value.map { if (it.id == id) it.copy(archived = archived) else it }
    }

    override suspend fun delete(id: String) {
        trips.value = trips.value.filterNot { it.id == id }
    }
}

/** In-memory [ItineraryRepository] fake ordered like the real DAO (day, orderInDay). */
class FakeItineraryRepository : ItineraryRepository {
    val items = MutableStateFlow<List<ItineraryItem>>(emptyList())

    override fun observeItemsForTrip(tripId: String): Flow<List<ItineraryItem>> =
        items.map { list ->
            list
                .filter { it.tripId == tripId }
                .sortedWith(compareBy({ it.dayIndex }, { it.orderInDay }))
        }

    override suspend fun getItem(id: String): ItineraryItem? = items.value.firstOrNull { it.id == id }

    override suspend fun save(item: ItineraryItem): ItineraryItem {
        items.value = items.value.filterNot { it.id == item.id } + item
        return item
    }

    override suspend fun saveAll(items: List<ItineraryItem>): List<ItineraryItem> {
        val ids = items.map { it.id }.toSet()
        this.items.value = this.items.value.filterNot { it.id in ids } + items
        return items
    }

    override suspend fun delete(id: String) {
        items.value = items.value.filterNot { it.id == id }
    }
}

/** Read-only [TrainRepository] fake — only the journey-picker paths are implemented. */
class FakeTrainRepository : TrainRepository {
    val tickets = MutableStateFlow<List<TrainTicket>>(emptyList())

    override fun observeActive(): Flow<List<TrainTicket>> = tickets.map { list -> list.filter { !it.archived } }

    override fun observeArchived(): Flow<List<TrainTicket>> = tickets.map { list -> list.filter { it.archived } }

    override fun observeTicket(id: String): Flow<TrainTicket?> = tickets.map { list -> list.firstOrNull { it.id == id } }

    override suspend fun getTicket(id: String): TrainTicket? = tickets.value.firstOrNull { it.id == id }

    override fun observePassengers(ticketId: String): Flow<List<TrainPassenger>> = MutableStateFlow(emptyList())

    override fun observeRouteStops(ticketId: String): Flow<List<TrainRouteStop>> = MutableStateFlow(emptyList())

    override suspend fun save(ticket: TrainTicket): TrainTicket {
        tickets.value = tickets.value.filterNot { it.id == ticket.id } + ticket
        return ticket
    }

    override suspend fun savePassengers(passengers: List<TrainPassenger>): List<TrainPassenger> = passengers

    override suspend fun replaceRouteStops(
        ticketId: String,
        stops: List<TrainRouteStop>,
    ): List<TrainRouteStop> = stops

    override fun observeCoaches(ticketId: String): Flow<List<TrainCoach>> = flowOf(emptyList())

    override suspend fun replaceCoaches(
        ticketId: String,
        coaches: List<TrainCoach>,
    ): List<TrainCoach> = coaches

    override suspend fun applyStatusResult(
        ticketId: String,
        result: TrainStatusResult,
    ) = Unit

    override suspend fun setArchived(
        id: String,
        archived: Boolean,
    ) = Unit

    override suspend fun delete(id: String) = Unit
}

/** Read-only [FlightRepository] fake — only the journey-picker paths are implemented. */
class FakeFlightRepository : FlightRepository {
    val flights = MutableStateFlow<List<FlightJourney>>(emptyList())

    override fun observeActive(): Flow<List<FlightJourney>> = flights.map { list -> list.filter { !it.archived } }

    override fun observeArchived(): Flow<List<FlightJourney>> = flights.map { list -> list.filter { it.archived } }

    override fun observeFlight(id: String): Flow<FlightJourney?> = flights.map { list -> list.firstOrNull { it.id == id } }

    override suspend fun getFlight(id: String): FlightJourney? = flights.value.firstOrNull { it.id == id }

    override suspend fun save(flight: FlightJourney): FlightJourney {
        flights.value = flights.value.filterNot { it.id == flight.id } + flight
        return flight
    }

    override suspend fun applyStatusResult(
        flightId: String,
        result: FlightStatusResult,
    ) = Unit

    override suspend fun setArchived(
        id: String,
        archived: Boolean,
    ) = Unit

    override suspend fun delete(id: String) = Unit
}
