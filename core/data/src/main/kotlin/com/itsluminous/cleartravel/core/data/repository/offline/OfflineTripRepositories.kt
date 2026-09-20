package com.itsluminous.cleartravel.core.data.repository.offline

import com.itsluminous.cleartravel.core.data.repository.ItineraryRepository
import com.itsluminous.cleartravel.core.data.repository.TripRepository
import com.itsluminous.cleartravel.core.database.dao.ChecklistDao
import com.itsluminous.cleartravel.core.database.dao.ItineraryDao
import com.itsluminous.cleartravel.core.database.dao.TripDao
import com.itsluminous.cleartravel.core.database.entity.toEntity
import com.itsluminous.cleartravel.core.database.entity.toModel
import com.itsluminous.cleartravel.core.model.ItineraryItem
import com.itsluminous.cleartravel.core.model.Trip
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/** Room-backed [TripRepository]. Every write bumps `updatedAt` via [clock] (ADR-002). */
@Singleton
class OfflineTripRepository
    @Inject
    constructor(
        private val tripDao: TripDao,
        private val itineraryDao: ItineraryDao,
        private val checklistDao: ChecklistDao,
        private val clock: Clock,
    ) : TripRepository {
        override fun observeActive(): Flow<List<Trip>> = tripDao.observeActive().map { rows -> rows.map { it.toModel() } }

        override fun observeArchived(): Flow<List<Trip>> = tripDao.observeArchived().map { rows -> rows.map { it.toModel() } }

        override fun observeTrip(id: String): Flow<Trip?> = tripDao.observeById(id).map { it?.toModel() }

        override suspend fun getTrip(id: String): Trip? = tripDao.getById(id)?.toModel()

        override suspend fun save(trip: Trip): Trip {
            val stamped = trip.copy(updatedAt = clock.instant())
            tripDao.upsert(stamped.toEntity())
            return stamped
        }

        override suspend fun setArchived(
            id: String,
            archived: Boolean,
        ) {
            val current = tripDao.getById(id) ?: return
            tripDao.upsert(current.copy(archived = archived, updatedAt = clock.instant()))
        }

        override suspend fun delete(id: String) {
            val now = clock.instant()
            itineraryDao.softDeleteForTrip(id, now)
            checklistDao.getForTrip(id).forEach { checklist ->
                checklistDao.softDeleteItemsFor(checklist.id, now)
                checklistDao.softDelete(checklist.id, now)
            }
            tripDao.softDelete(id, now)
        }
    }

/** Room-backed [ItineraryRepository]. Every write bumps `updatedAt` (ADR-002). */
@Singleton
class OfflineItineraryRepository
    @Inject
    constructor(
        private val itineraryDao: ItineraryDao,
        private val clock: Clock,
    ) : ItineraryRepository {
        override fun observeItemsForTrip(tripId: String): Flow<List<ItineraryItem>> =
            itineraryDao.observeForTrip(tripId).map { rows -> rows.map { it.toModel() } }

        override suspend fun getItem(id: String): ItineraryItem? = itineraryDao.getById(id)?.toModel()

        override suspend fun save(item: ItineraryItem): ItineraryItem = saveAll(listOf(item)).first()

        override suspend fun saveAll(items: List<ItineraryItem>): List<ItineraryItem> {
            val now = clock.instant()
            val stamped = items.map { it.copy(updatedAt = now) }
            itineraryDao.upsert(stamped.map { it.toEntity() })
            return stamped
        }

        override suspend fun delete(id: String) {
            itineraryDao.softDelete(id, clock.instant())
        }
    }
