package com.itsluminous.cleartravel.core.data.repository.offline

import com.itsluminous.cleartravel.core.data.provider.FlightStatusResult
import com.itsluminous.cleartravel.core.data.provider.TrainStatusResult
import com.itsluminous.cleartravel.core.data.repository.AttachmentRepository
import com.itsluminous.cleartravel.core.data.repository.FlightRepository
import com.itsluminous.cleartravel.core.data.repository.TrainRepository
import com.itsluminous.cleartravel.core.database.dao.AttachmentDao
import com.itsluminous.cleartravel.core.database.dao.FlightDao
import com.itsluminous.cleartravel.core.database.dao.TrainDao
import com.itsluminous.cleartravel.core.database.entity.toEntity
import com.itsluminous.cleartravel.core.database.entity.toModel
import com.itsluminous.cleartravel.core.model.Attachment
import com.itsluminous.cleartravel.core.model.AttachmentOwnerType
import com.itsluminous.cleartravel.core.model.FlightJourney
import com.itsluminous.cleartravel.core.model.TrainCoach
import com.itsluminous.cleartravel.core.model.TrainPassenger
import com.itsluminous.cleartravel.core.model.TrainRouteStop
import com.itsluminous.cleartravel.core.model.TrainTicket
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/** Room-backed [TrainRepository]. Every write bumps `updatedAt` (ADR-002). */
@Singleton
class OfflineTrainRepository
    @Inject
    constructor(
        private val trainDao: TrainDao,
        private val attachmentDao: AttachmentDao,
        private val clock: Clock,
    ) : TrainRepository {
        override fun observeActive(): Flow<List<TrainTicket>> = trainDao.observeActive().map { rows -> rows.map { it.toModel() } }

        override fun observeArchived(): Flow<List<TrainTicket>> = trainDao.observeArchived().map { rows -> rows.map { it.toModel() } }

        override fun observeTicket(id: String): Flow<TrainTicket?> = trainDao.observeById(id).map { it?.toModel() }

        override suspend fun getTicket(id: String): TrainTicket? = trainDao.getById(id)?.toModel()

        override fun observePassengers(ticketId: String): Flow<List<TrainPassenger>> =
            trainDao.observePassengers(ticketId).map { rows -> rows.map { it.toModel() } }

        override fun observeRouteStops(ticketId: String): Flow<List<TrainRouteStop>> =
            trainDao.observeRouteStops(ticketId).map { rows -> rows.map { it.toModel() } }

        override fun observeCoaches(ticketId: String): Flow<List<TrainCoach>> =
            trainDao.observeCoaches(ticketId).map { rows -> rows.map { it.toModel() } }

        override suspend fun save(ticket: TrainTicket): TrainTicket {
            val stamped = ticket.copy(updatedAt = clock.instant())
            trainDao.upsert(stamped.toEntity())
            return stamped
        }

        override suspend fun savePassengers(passengers: List<TrainPassenger>): List<TrainPassenger> {
            val now = clock.instant()
            val stamped = passengers.map { it.copy(updatedAt = now) }
            trainDao.upsertPassengers(stamped.map { it.toEntity() })
            return stamped
        }

        override suspend fun replaceRouteStops(
            ticketId: String,
            stops: List<TrainRouteStop>,
        ): List<TrainRouteStop> {
            val now = clock.instant()
            trainDao.softDeleteRouteStopsFor(ticketId, now)
            val stamped = stops.map { it.copy(ticketId = ticketId, updatedAt = now) }
            trainDao.upsertRouteStops(stamped.map { it.toEntity() })
            return stamped
        }

        override suspend fun replaceCoaches(
            ticketId: String,
            coaches: List<TrainCoach>,
        ): List<TrainCoach> {
            val now = clock.instant()
            trainDao.softDeleteCoachesFor(ticketId, now)
            val stamped = coaches.map { it.copy(ticketId = ticketId, updatedAt = now) }
            trainDao.upsertCoaches(stamped.map { it.toEntity() })
            return stamped
        }

        override suspend fun applyStatusResult(
            ticketId: String,
            result: TrainStatusResult,
        ) {
            val ticket = trainDao.getById(ticketId) ?: return
            val now = clock.instant()
            val passengers = trainDao.getPassengers(ticketId)
            val updated =
                passengers.mapIndexedNotNull { index, passenger ->
                    val status = result.passengers.getOrNull(index) ?: return@mapIndexedNotNull null
                    passenger.copy(
                        currentStatus = status.currentStatus,
                        coach = status.coach.ifEmpty { passenger.coach },
                        seatBerth = status.seatBerth.ifEmpty { passenger.seatBerth },
                        updatedAt = now,
                    )
                }
            if (updated.isNotEmpty()) {
                trainDao.upsertPassengers(updated)
            }
            trainDao.upsert(ticket.copy(lastFetchedAt = result.fetchedAt, updatedAt = now))
        }

        override suspend fun setArchived(
            id: String,
            archived: Boolean,
        ) {
            val current = trainDao.getById(id) ?: return
            trainDao.upsert(current.copy(archived = archived, updatedAt = clock.instant()))
        }

        override suspend fun delete(id: String) {
            val now = clock.instant()
            trainDao.softDeletePassengersFor(id, now)
            trainDao.softDeleteRouteStopsFor(id, now)
            trainDao.softDeleteCoachesFor(id, now)
            attachmentDao.softDeleteForOwner(AttachmentOwnerType.TRAIN, id, now)
            trainDao.softDelete(id, now)
        }
    }

/** Room-backed [FlightRepository]. Every write bumps `updatedAt` (ADR-002). */
@Singleton
class OfflineFlightRepository
    @Inject
    constructor(
        private val flightDao: FlightDao,
        private val attachmentDao: AttachmentDao,
        private val clock: Clock,
    ) : FlightRepository {
        override fun observeActive(): Flow<List<FlightJourney>> = flightDao.observeActive().map { rows -> rows.map { it.toModel() } }

        override fun observeArchived(): Flow<List<FlightJourney>> = flightDao.observeArchived().map { rows -> rows.map { it.toModel() } }

        override fun observeFlight(id: String): Flow<FlightJourney?> = flightDao.observeById(id).map { it?.toModel() }

        override suspend fun getFlight(id: String): FlightJourney? = flightDao.getById(id)?.toModel()

        override suspend fun save(flight: FlightJourney): FlightJourney {
            val stamped = flight.copy(updatedAt = clock.instant())
            flightDao.upsert(stamped.toEntity())
            return stamped
        }

        override suspend fun applyStatusResult(
            flightId: String,
            result: FlightStatusResult,
        ) {
            val current = flightDao.getById(flightId) ?: return
            flightDao.upsert(
                current.copy(
                    status = result.status,
                    schedDep = result.schedDep ?: current.schedDep,
                    schedArr = result.schedArr ?: current.schedArr,
                    estDep = result.estDep ?: current.estDep,
                    estArr = result.estArr ?: current.estArr,
                    depTerminal = result.depTerminal.ifEmpty { current.depTerminal },
                    depGate = result.depGate.ifEmpty { current.depGate },
                    arrTerminal = result.arrTerminal.ifEmpty { current.arrTerminal },
                    arrGate = result.arrGate.ifEmpty { current.arrGate },
                    baggageBelt = result.baggageBelt.ifEmpty { current.baggageBelt },
                    aircraftType = result.aircraftType.ifEmpty { current.aircraftType },
                    lastFetchedAt = result.fetchedAt,
                    updatedAt = clock.instant(),
                ),
            )
        }

        override suspend fun setArchived(
            id: String,
            archived: Boolean,
        ) {
            val current = flightDao.getById(id) ?: return
            flightDao.upsert(current.copy(archived = archived, updatedAt = clock.instant()))
        }

        override suspend fun delete(id: String) {
            val now = clock.instant()
            attachmentDao.softDeleteForOwner(AttachmentOwnerType.FLIGHT, id, now)
            flightDao.softDelete(id, now)
        }
    }

/** Room-backed [AttachmentRepository]. Every write bumps `updatedAt` (ADR-002). */
@Singleton
class OfflineAttachmentRepository
    @Inject
    constructor(
        private val attachmentDao: AttachmentDao,
        private val clock: Clock,
    ) : AttachmentRepository {
        override fun observeForOwner(
            ownerType: AttachmentOwnerType,
            ownerId: String,
        ): Flow<List<Attachment>> = attachmentDao.observeForOwner(ownerType, ownerId).map { rows -> rows.map { it.toModel() } }

        override suspend fun getAttachment(id: String): Attachment? = attachmentDao.getById(id)?.toModel()

        override suspend fun getPendingDriveUploads(): List<Attachment> = attachmentDao.getPendingDriveUploads().map { it.toModel() }

        override suspend fun save(attachment: Attachment): Attachment {
            val stamped = attachment.copy(updatedAt = clock.instant())
            attachmentDao.upsert(stamped.toEntity())
            return stamped
        }

        override suspend fun delete(id: String) {
            attachmentDao.softDelete(id, clock.instant())
        }

        override suspend fun deleteForOwner(
            ownerType: AttachmentOwnerType,
            ownerId: String,
        ) {
            attachmentDao.softDeleteForOwner(ownerType, ownerId, clock.instant())
        }
    }
