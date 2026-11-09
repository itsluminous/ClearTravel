package com.itsluminous.cleartravel.ui

import com.itsluminous.cleartravel.core.data.provider.FlightStatusResult
import com.itsluminous.cleartravel.core.data.provider.TrainStatusResult
import com.itsluminous.cleartravel.core.data.repository.FlightRepository
import com.itsluminous.cleartravel.core.data.repository.TrainRepository
import com.itsluminous.cleartravel.core.model.FlightJourney
import com.itsluminous.cleartravel.core.model.FlightPassenger
import com.itsluminous.cleartravel.core.model.FlightWithPassengers
import com.itsluminous.cleartravel.core.model.TrainCoach
import com.itsluminous.cleartravel.core.model.TrainPassenger
import com.itsluminous.cleartravel.core.model.TrainRouteStop
import com.itsluminous.cleartravel.core.model.TrainTicket
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.time.LocalDate

/**
 * Read-only [TrainRepository] fake for the shell's tests: only the list reads are
 * meaningful, and they honour the Room contract — `observeActive` / `observeArchived`
 * never emit a tombstoned ticket.
 */
class FakeTrainRepository : TrainRepository {
    val tickets = MutableStateFlow<List<TrainTicket>>(emptyList())

    override fun observeActive(): Flow<List<TrainTicket>> = tickets.map { list -> list.filter { it.deletedAt == null && !it.archived } }

    override fun observeArchived(): Flow<List<TrainTicket>> = tickets.map { list -> list.filter { it.deletedAt == null && it.archived } }

    override fun observeTicket(id: String): Flow<TrainTicket?> = tickets.map { list -> list.firstOrNull { it.id == id } }

    override suspend fun getTicket(id: String): TrainTicket? = tickets.value.firstOrNull { it.id == id }

    override suspend fun findByPnr(pnr: String): TrainTicket? = null

    override fun observePassengers(ticketId: String): Flow<List<TrainPassenger>> = flowOf(emptyList())

    override fun observeRouteStops(ticketId: String): Flow<List<TrainRouteStop>> = flowOf(emptyList())

    override fun observeCoaches(ticketId: String): Flow<List<TrainCoach>> = flowOf(emptyList())

    override suspend fun save(ticket: TrainTicket): TrainTicket {
        tickets.value = tickets.value.filterNot { it.id == ticket.id } + ticket
        return ticket
    }

    override suspend fun savePassengers(passengers: List<TrainPassenger>): List<TrainPassenger> = passengers

    override suspend fun replaceRouteStops(
        ticketId: String,
        stops: List<TrainRouteStop>,
    ): List<TrainRouteStop> = stops

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

/** Read-only [FlightRepository] fake — list reads only, tombstones excluded like Room. */
class FakeFlightRepository : FlightRepository {
    val flights = MutableStateFlow<List<FlightJourney>>(emptyList())

    override fun observeActive(): Flow<List<FlightJourney>> = flights.map { list -> list.filter { it.deletedAt == null && !it.archived } }

    override fun observeArchived(): Flow<List<FlightJourney>> = flights.map { list -> list.filter { it.deletedAt == null && it.archived } }

    override fun observeActiveWithPassengers(): Flow<List<FlightWithPassengers>> =
        observeActive().map { list -> list.map { FlightWithPassengers(it) } }

    override fun observeArchivedWithPassengers(): Flow<List<FlightWithPassengers>> =
        observeArchived().map { list -> list.map { FlightWithPassengers(it) } }

    override fun observeFlight(id: String): Flow<FlightJourney?> = flights.map { list -> list.firstOrNull { it.id == id } }

    override suspend fun getFlight(id: String): FlightJourney? = flights.value.firstOrNull { it.id == id }

    override suspend fun findByFlight(
        airlineIata: String,
        flightNumber: String,
        date: LocalDate,
    ): FlightJourney? = null

    override suspend fun save(flight: FlightJourney): FlightJourney {
        flights.value = flights.value.filterNot { it.id == flight.id } + flight
        return flight
    }

    override fun observePassengers(flightId: String): Flow<List<FlightPassenger>> = flowOf(emptyList())

    override suspend fun getPassengers(flightId: String): List<FlightPassenger> = emptyList()

    override suspend fun savePassengers(
        flightId: String,
        passengers: List<FlightPassenger>,
    ): List<FlightPassenger> = passengers

    override suspend fun replacePassengers(
        flightId: String,
        passengers: List<FlightPassenger>,
    ): List<FlightPassenger> = passengers

    override suspend fun deletePassenger(id: String) = Unit

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
