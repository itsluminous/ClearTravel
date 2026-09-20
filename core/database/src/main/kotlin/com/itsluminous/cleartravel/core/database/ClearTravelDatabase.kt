package com.itsluminous.cleartravel.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.itsluminous.cleartravel.core.database.converter.Converters
import com.itsluminous.cleartravel.core.database.dao.AttachmentDao
import com.itsluminous.cleartravel.core.database.dao.ChecklistDao
import com.itsluminous.cleartravel.core.database.dao.ChecklistPresetDao
import com.itsluminous.cleartravel.core.database.dao.FlightDao
import com.itsluminous.cleartravel.core.database.dao.ItineraryDao
import com.itsluminous.cleartravel.core.database.dao.TrainDao
import com.itsluminous.cleartravel.core.database.dao.TripDao
import com.itsluminous.cleartravel.core.database.entity.AttachmentEntity
import com.itsluminous.cleartravel.core.database.entity.ChecklistEntity
import com.itsluminous.cleartravel.core.database.entity.ChecklistItemEntity
import com.itsluminous.cleartravel.core.database.entity.ChecklistPresetEntity
import com.itsluminous.cleartravel.core.database.entity.ChecklistPresetItemEntity
import com.itsluminous.cleartravel.core.database.entity.FlightJourneyEntity
import com.itsluminous.cleartravel.core.database.entity.ItineraryItemEntity
import com.itsluminous.cleartravel.core.database.entity.TrainPassengerEntity
import com.itsluminous.cleartravel.core.database.entity.TrainRouteStopEntity
import com.itsluminous.cleartravel.core.database.entity.TrainTicketEntity
import com.itsluminous.cleartravel.core.database.entity.TripEntity

/**
 * The single on-device Room database — the offline-first source of truth for every
 * feature (ADR-004). Schema history is exported to `core/database/schemas/` and
 * committed; bump [DatabaseConstants] docs and add a migration on every version bump.
 */
@Database(
    entities = [
        TripEntity::class,
        ItineraryItemEntity::class,
        ChecklistEntity::class,
        ChecklistItemEntity::class,
        ChecklistPresetEntity::class,
        ChecklistPresetItemEntity::class,
        TrainTicketEntity::class,
        TrainPassengerEntity::class,
        TrainRouteStopEntity::class,
        FlightJourneyEntity::class,
        AttachmentEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class ClearTravelDatabase : RoomDatabase() {
    abstract fun tripDao(): TripDao

    abstract fun itineraryDao(): ItineraryDao

    abstract fun checklistDao(): ChecklistDao

    abstract fun checklistPresetDao(): ChecklistPresetDao

    abstract fun trainDao(): TrainDao

    abstract fun flightDao(): FlightDao

    abstract fun attachmentDao(): AttachmentDao
}
