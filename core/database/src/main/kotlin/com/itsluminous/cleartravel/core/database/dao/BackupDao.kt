package com.itsluminous.cleartravel.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
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
 * Backup engine access (ADR-015). Two deliberate deviations from every other DAO:
 *
 * 1. Dump queries return FULL tables INCLUDING tombstoned rows — a backup must carry
 *    deletions so imports on other devices can honor them (ADR-002).
 * 2. Upserts write rows EXACTLY as given, preserving `id`, `updated_at` and
 *    `deleted_at` — the merge algorithm already resolved last-write-wins, so bumping
 *    `updated_at` here would corrupt future merges. Room's `@Upsert` never touches
 *    column values, which is exactly the required semantics; only repositories bump
 *    timestamps.
 *
 * Never expose this DAO to feature modules — it exists solely for [BackupManager]
 * implementations in `core:data`.
 */
@Dao
interface BackupDao {
    // ---- Full dumps (tombstones included) ----

    @Query("SELECT * FROM trips")
    suspend fun dumpTrips(): List<TripEntity>

    @Query("SELECT * FROM itinerary_items")
    suspend fun dumpItineraryItems(): List<ItineraryItemEntity>

    @Query("SELECT * FROM checklists")
    suspend fun dumpChecklists(): List<ChecklistEntity>

    @Query("SELECT * FROM checklist_items")
    suspend fun dumpChecklistItems(): List<ChecklistItemEntity>

    @Query("SELECT * FROM checklist_presets")
    suspend fun dumpChecklistPresets(): List<ChecklistPresetEntity>

    @Query("SELECT * FROM checklist_preset_items")
    suspend fun dumpChecklistPresetItems(): List<ChecklistPresetItemEntity>

    @Query("SELECT * FROM train_tickets")
    suspend fun dumpTrainTickets(): List<TrainTicketEntity>

    @Query("SELECT * FROM train_passengers")
    suspend fun dumpTrainPassengers(): List<TrainPassengerEntity>

    @Query("SELECT * FROM train_route_stops")
    suspend fun dumpTrainRouteStops(): List<TrainRouteStopEntity>

    @Query("SELECT * FROM flight_journeys")
    suspend fun dumpFlightJourneys(): List<FlightJourneyEntity>

    @Query("SELECT * FROM attachments")
    suspend fun dumpAttachments(): List<AttachmentEntity>

    // ---- Timestamp-preserving upserts ----

    @Upsert
    suspend fun upsertTrips(rows: List<TripEntity>)

    @Upsert
    suspend fun upsertItineraryItems(rows: List<ItineraryItemEntity>)

    @Upsert
    suspend fun upsertChecklists(rows: List<ChecklistEntity>)

    @Upsert
    suspend fun upsertChecklistItems(rows: List<ChecklistItemEntity>)

    @Upsert
    suspend fun upsertChecklistPresets(rows: List<ChecklistPresetEntity>)

    @Upsert
    suspend fun upsertChecklistPresetItems(rows: List<ChecklistPresetItemEntity>)

    @Upsert
    suspend fun upsertTrainTickets(rows: List<TrainTicketEntity>)

    @Upsert
    suspend fun upsertTrainPassengers(rows: List<TrainPassengerEntity>)

    @Upsert
    suspend fun upsertTrainRouteStops(rows: List<TrainRouteStopEntity>)

    @Upsert
    suspend fun upsertFlightJourneys(rows: List<FlightJourneyEntity>)

    @Upsert
    suspend fun upsertAttachments(rows: List<AttachmentEntity>)
}
