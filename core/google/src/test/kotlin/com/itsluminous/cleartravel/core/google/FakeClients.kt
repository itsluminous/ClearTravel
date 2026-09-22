package com.itsluminous.cleartravel.core.google

import com.itsluminous.cleartravel.core.data.provider.FlightStatusResult
import com.itsluminous.cleartravel.core.data.provider.TrainStatusResult
import com.itsluminous.cleartravel.core.data.repository.AttachmentRepository
import com.itsluminous.cleartravel.core.data.repository.FlightIdentity
import com.itsluminous.cleartravel.core.data.repository.FlightRepository
import com.itsluminous.cleartravel.core.data.repository.ItineraryRepository
import com.itsluminous.cleartravel.core.data.repository.TrainRepository
import com.itsluminous.cleartravel.core.data.repository.TripRepository
import com.itsluminous.cleartravel.core.google.calendar.CalendarClient
import com.itsluminous.cleartravel.core.google.calendar.CalendarEvent
import com.itsluminous.cleartravel.core.google.calendar.CalendarSyncStateStore
import com.itsluminous.cleartravel.core.google.calendar.SyncedEventRecord
import com.itsluminous.cleartravel.core.model.Attachment
import com.itsluminous.cleartravel.core.model.AttachmentOwnerType
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
import java.time.LocalDate

/** In-memory [CalendarClient] recording every call — tests never touch live APIs. */
class FakeCalendarClient : CalendarClient {
    val calendars = mutableMapOf<String, String>()
    val events = mutableMapOf<String, Pair<String, CalendarEvent>>()
    var createCalendarCalls = 0
        private set
    var insertCalls = 0
        private set
    var updateCalls = 0
        private set
    var deleteCalls = 0
        private set
    var failNextInsert: Exception? = null
    private var nextEventId = 1

    override suspend fun calendarSummary(calendarId: String): String? = calendars[calendarId]

    override suspend fun createCalendar(summary: String): String {
        createCalendarCalls++
        val id = "cal-${calendars.size + 1}"
        calendars[id] = summary
        return id
    }

    override suspend fun deleteCalendar(calendarId: String) {
        calendars.remove(calendarId)
        events.entries.removeIf { it.value.first == calendarId }
    }

    override suspend fun insertEvent(
        calendarId: String,
        event: CalendarEvent,
    ): String {
        failNextInsert?.let {
            failNextInsert = null
            throw it
        }
        insertCalls++
        val id = "event-${nextEventId++}"
        events[id] = calendarId to event
        return id
    }

    override suspend fun updateEvent(
        calendarId: String,
        eventId: String,
        event: CalendarEvent,
    ) {
        updateCalls++
        events[eventId] = calendarId to event
    }

    override suspend fun deleteEvent(
        calendarId: String,
        eventId: String,
    ) {
        deleteCalls++
        events.remove(eventId)
    }
}

/** In-memory [CalendarSyncStateStore]. */
class FakeCalendarSyncStateStore : CalendarSyncStateStore {
    private val records = mutableMapOf<String, SyncedEventRecord>()

    override suspend fun all(): Map<String, SyncedEventRecord> = records.toMap()

    override suspend fun put(
        rowId: String,
        record: SyncedEventRecord,
    ) {
        records[rowId] = record
    }

    override suspend fun remove(rowId: String) {
        records.remove(rowId)
    }

    override suspend fun clear() {
        records.clear()
    }
}

/** In-memory [TripRepository] (reads + save/delete used by the engine and heuristics). */
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

/** In-memory [ItineraryRepository]. */
class FakeItineraryRepository : ItineraryRepository {
    val items = MutableStateFlow<List<ItineraryItem>>(emptyList())

    override fun observeItemsForTrip(tripId: String): Flow<List<ItineraryItem>> = items.map { list -> list.filter { it.tripId == tripId } }

    override fun observeItemsLinkedToJourney(journeyId: String): Flow<List<ItineraryItem>> =
        items.map { list -> list.filter { it.linkedJourneyId == journeyId } }

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

/** In-memory [TrainRepository]. */
class FakeTrainRepository : TrainRepository {
    val tickets = MutableStateFlow<List<TrainTicket>>(emptyList())
    val passengers = MutableStateFlow<List<TrainPassenger>>(emptyList())
    val routeStops = MutableStateFlow<List<TrainRouteStop>>(emptyList())

    override fun observeActive(): Flow<List<TrainTicket>> = tickets.map { list -> list.filter { !it.archived } }

    override fun observeArchived(): Flow<List<TrainTicket>> = tickets.map { list -> list.filter { it.archived } }

    override fun observeTicket(id: String): Flow<TrainTicket?> = tickets.map { list -> list.firstOrNull { it.id == id } }

    override suspend fun getTicket(id: String): TrainTicket? = tickets.value.firstOrNull { it.id == id }

    override suspend fun findByPnr(pnr: String): TrainTicket? =
        tickets.value.firstOrNull { it.deletedAt == null && it.pnr.trim().equals(pnr.trim(), ignoreCase = true) }

    override fun observePassengers(ticketId: String): Flow<List<TrainPassenger>> =
        passengers.map { list -> list.filter { it.ticketId == ticketId } }

    override fun observeRouteStops(ticketId: String): Flow<List<TrainRouteStop>> =
        routeStops.map { list -> list.filter { it.ticketId == ticketId } }

    override suspend fun save(ticket: TrainTicket): TrainTicket {
        tickets.value = tickets.value.filterNot { it.id == ticket.id } + ticket
        return ticket
    }

    override suspend fun savePassengers(passengers: List<TrainPassenger>): List<TrainPassenger> {
        this.passengers.value = this.passengers.value.filterNot { p -> passengers.any { it.id == p.id } } + passengers
        return passengers
    }

    override suspend fun replaceRouteStops(
        ticketId: String,
        stops: List<TrainRouteStop>,
    ): List<TrainRouteStop> {
        routeStops.value = routeStops.value.filterNot { it.ticketId == ticketId } + stops
        return stops
    }

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
    ) {
        tickets.value = tickets.value.map { if (it.id == id) it.copy(archived = archived) else it }
    }

    override suspend fun delete(id: String) {
        tickets.value = tickets.value.filterNot { it.id == id }
    }
}

/** In-memory [FlightRepository]. */
class FakeFlightRepository : FlightRepository {
    val flights = MutableStateFlow<List<FlightJourney>>(emptyList())

    override fun observeActive(): Flow<List<FlightJourney>> = flights.map { list -> list.filter { !it.archived } }

    override fun observeArchived(): Flow<List<FlightJourney>> = flights.map { list -> list.filter { it.archived } }

    override fun observeFlight(id: String): Flow<FlightJourney?> = flights.map { list -> list.firstOrNull { it.id == id } }

    override suspend fun getFlight(id: String): FlightJourney? = flights.value.firstOrNull { it.id == id }

    override suspend fun findByFlight(
        airlineIata: String,
        flightNumber: String,
        date: LocalDate,
    ): FlightJourney? =
        flights.value.firstOrNull {
            it.deletedAt == null &&
                FlightIdentity.normalizeAirline(it.airlineIata) == FlightIdentity.normalizeAirline(airlineIata) &&
                FlightIdentity.normalizeFlightNumber(it.flightNumber) == FlightIdentity.normalizeFlightNumber(flightNumber) &&
                it.date == date
        }

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
    ) {
        flights.value = flights.value.map { if (it.id == id) it.copy(archived = archived) else it }
    }

    override suspend fun delete(id: String) {
        flights.value = flights.value.filterNot { it.id == id }
    }
}

/** In-memory [AttachmentRepository]. */
class FakeAttachmentRepository : AttachmentRepository {
    val attachments = MutableStateFlow<List<Attachment>>(emptyList())

    override fun observeForOwner(
        ownerType: AttachmentOwnerType,
        ownerId: String,
    ): Flow<List<Attachment>> = attachments.map { list -> list.filter { it.ownerType == ownerType && it.ownerId == ownerId } }

    override suspend fun getAttachment(id: String): Attachment? = attachments.value.firstOrNull { it.id == id }

    override suspend fun getPendingDriveUploads(): List<Attachment> = attachments.value.filter { it.driveFileId == null }

    override suspend fun save(attachment: Attachment): Attachment {
        attachments.value = attachments.value.filterNot { it.id == attachment.id } + attachment
        return attachment
    }

    override suspend fun delete(id: String) {
        attachments.value = attachments.value.filterNot { it.id == id }
    }

    override suspend fun deleteForOwner(
        ownerType: AttachmentOwnerType,
        ownerId: String,
    ) {
        attachments.value = attachments.value.filterNot { it.ownerType == ownerType && it.ownerId == ownerId }
    }
}
