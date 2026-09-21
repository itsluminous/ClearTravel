package com.itsluminous.cleartravel.feature.trains

import com.itsluminous.cleartravel.core.data.provider.TrainStatusResult
import com.itsluminous.cleartravel.core.data.repository.TrainRepository
import com.itsluminous.cleartravel.core.model.TrainCoach
import com.itsluminous.cleartravel.core.model.TrainPassenger
import com.itsluminous.cleartravel.core.model.TrainRouteStop
import com.itsluminous.cleartravel.core.model.TrainTicket
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.time.Instant

/**
 * In-memory [TrainRepository] fake for ViewModel tests. Mirrors the real
 * implementation's semantics: tombstone filtering, `updatedAt` bumps, and
 * [applyStatusResult]'s by-position merge (ADR-005).
 */
class FakeTrainRepository(
    private val now: () -> Instant = Instant::now,
) : TrainRepository {
    private val tickets = MutableStateFlow<Map<String, TrainTicket>>(emptyMap())
    private val passengers = MutableStateFlow<Map<String, TrainPassenger>>(emptyMap())
    private val routeStops = MutableStateFlow<Map<String, TrainRouteStop>>(emptyMap())
    private val coaches = MutableStateFlow<Map<String, TrainCoach>>(emptyMap())

    val savedTickets = mutableListOf<TrainTicket>()
    val savedPassengerBatches = mutableListOf<List<TrainPassenger>>()
    val appliedResults = mutableListOf<Pair<String, TrainStatusResult>>()
    val deletedIds = mutableListOf<String>()
    val replacedCoachBatches = mutableListOf<Pair<String, List<TrainCoach>>>()

    fun seed(
        ticket: TrainTicket,
        ticketPassengers: List<TrainPassenger> = emptyList(),
        stops: List<TrainRouteStop> = emptyList(),
        ticketCoaches: List<TrainCoach> = emptyList(),
    ) {
        tickets.value += ticket.id to ticket
        passengers.value += ticketPassengers.associateBy(TrainPassenger::id)
        routeStops.value += stops.associateBy(TrainRouteStop::id)
        coaches.value += ticketCoaches.associateBy(TrainCoach::id)
    }

    override fun observeActive(): Flow<List<TrainTicket>> =
        tickets.map { all ->
            all.values.filter { it.deletedAt == null && !it.archived }.sortedBy(TrainTicket::updatedAt)
        }

    override fun observeArchived(): Flow<List<TrainTicket>> =
        tickets.map { all ->
            all.values.filter { it.deletedAt == null && it.archived }.sortedBy(TrainTicket::updatedAt)
        }

    override fun observeTicket(id: String): Flow<TrainTicket?> = tickets.map { all -> all[id]?.takeIf { it.deletedAt == null } }

    override suspend fun getTicket(id: String): TrainTicket? = tickets.value[id]?.takeIf { it.deletedAt == null }

    override fun observePassengers(ticketId: String): Flow<List<TrainPassenger>> =
        passengers.map { all ->
            all.values
                .filter { it.ticketId == ticketId && it.deletedAt == null }
                .sortedBy(TrainPassenger::sortOrder)
        }

    override fun observeRouteStops(ticketId: String): Flow<List<TrainRouteStop>> =
        routeStops.map { all ->
            all.values
                .filter { it.ticketId == ticketId && it.deletedAt == null }
                .sortedBy(TrainRouteStop::sortOrder)
        }

    override fun observeCoaches(ticketId: String): Flow<List<TrainCoach>> =
        coaches.map { all ->
            all.values
                .filter { it.ticketId == ticketId && it.deletedAt == null }
                .sortedBy(TrainCoach::sortOrder)
        }

    override suspend fun save(ticket: TrainTicket): TrainTicket {
        val stamped = ticket.copy(updatedAt = now())
        tickets.value += stamped.id to stamped
        savedTickets += stamped
        return stamped
    }

    override suspend fun savePassengers(passengers: List<TrainPassenger>): List<TrainPassenger> {
        val stamped = passengers.map { it.copy(updatedAt = now()) }
        this.passengers.value += stamped.associateBy(TrainPassenger::id)
        savedPassengerBatches += stamped
        return stamped
    }

    override suspend fun replaceRouteStops(
        ticketId: String,
        stops: List<TrainRouteStop>,
    ): List<TrainRouteStop> {
        val stamped = stops.map { it.copy(ticketId = ticketId, updatedAt = now()) }
        routeStops.value =
            routeStops.value.mapValues { (_, stop) ->
                if (stop.ticketId == ticketId) stop.copy(deletedAt = now()) else stop
            } + stamped.associateBy(TrainRouteStop::id)
        return stamped
    }

    override suspend fun replaceCoaches(
        ticketId: String,
        coaches: List<TrainCoach>,
    ): List<TrainCoach> {
        val stamped = coaches.map { it.copy(ticketId = ticketId, updatedAt = now()) }
        replacedCoachBatches += ticketId to stamped
        this.coaches.value =
            this.coaches.value.mapValues { (_, coach) ->
                if (coach.ticketId == ticketId) coach.copy(deletedAt = now()) else coach
            } + stamped.associateBy(TrainCoach::id)
        return stamped
    }

    override suspend fun applyStatusResult(
        ticketId: String,
        result: TrainStatusResult,
    ) {
        val ticket = tickets.value[ticketId] ?: return
        appliedResults += ticketId to result
        val ordered =
            passengers.value.values
                .filter { it.ticketId == ticketId && it.deletedAt == null }
                .sortedBy(TrainPassenger::sortOrder)
        val updated =
            ordered.mapIndexedNotNull { index, passenger ->
                val status = result.passengers.getOrNull(index) ?: return@mapIndexedNotNull null
                passenger.copy(
                    currentStatus = status.currentStatus,
                    coach = status.coach.ifEmpty { passenger.coach },
                    seatBerth = status.seatBerth.ifEmpty { passenger.seatBerth },
                    updatedAt = now(),
                )
            }
        val inserted =
            result.passengers.drop(ordered.size).mapIndexed { offset, status ->
                TrainPassenger(
                    ticketId = ticketId,
                    coach = status.coach,
                    seatBerth = status.seatBerth,
                    bookingStatus = status.bookingStatus,
                    currentStatus = status.currentStatus,
                    sortOrder = ordered.size + offset,
                    updatedAt = now(),
                )
            }
        passengers.value += (updated + inserted).associateBy(TrainPassenger::id)
        tickets.value += ticketId to
            ticket.copy(
                trainNumber = ticket.trainNumber.ifBlank { result.trainNumber },
                trainName = ticket.trainName.ifBlank { result.trainName },
                journeyDate = ticket.journeyDate ?: result.journeyDate,
                fromStation = ticket.fromStation.ifBlank { result.fromStation },
                toStation = ticket.toStation.ifBlank { result.toStation },
                travelClass = ticket.travelClass.ifBlank { result.travelClass },
                lastFetchedAt = result.fetchedAt,
                updatedAt = now(),
            )
    }

    override suspend fun setArchived(
        id: String,
        archived: Boolean,
    ) {
        val current = tickets.value[id] ?: return
        tickets.value += id to current.copy(archived = archived, updatedAt = now())
    }

    override suspend fun delete(id: String) {
        deletedIds += id
        val current = tickets.value[id] ?: return
        tickets.value += id to current.copy(deletedAt = now())
        passengers.value =
            passengers.value.mapValues { (_, passenger) ->
                if (passenger.ticketId == id) passenger.copy(deletedAt = now()) else passenger
            }
    }
}
