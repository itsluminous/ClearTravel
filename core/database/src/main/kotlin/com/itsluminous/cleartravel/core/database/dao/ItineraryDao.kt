package com.itsluminous.cleartravel.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.itsluminous.cleartravel.core.database.entity.ItineraryItemEntity
import com.itsluminous.cleartravel.core.model.ItineraryItemType
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/** Itinerary items of a trip. All read queries exclude tombstoned rows (ADR-002). */
@Dao
interface ItineraryDao {
    @Query("SELECT * FROM itinerary_items WHERE trip_id = :tripId AND deleted_at IS NULL ORDER BY day_index, order_in_day")
    fun observeForTrip(tripId: String): Flow<List<ItineraryItemEntity>>

    /** Reverse lookup (ADR-028): live items across all trips linked to one journey. */
    @Query(
        "SELECT * FROM itinerary_items WHERE linked_journey_id = :journeyId AND deleted_at IS NULL " +
            "ORDER BY day_index, order_in_day",
    )
    fun observeLinkedToJourney(journeyId: String): Flow<List<ItineraryItemEntity>>

    @Query("SELECT * FROM itinerary_items WHERE id = :id AND deleted_at IS NULL")
    suspend fun getById(id: String): ItineraryItemEntity?

    /**
     * ADR-048: live PLACE items of EVERY trip that have no coordinates yet — the
     * location backfill's work list. Ordered by trip, then (dayIndex, orderInDay).
     */
    @Query(
        "SELECT * FROM itinerary_items WHERE type = :type AND deleted_at IS NULL " +
            "AND (latitude IS NULL OR longitude IS NULL) ORDER BY trip_id, day_index, order_in_day",
    )
    suspend fun getWithoutCoordinates(type: ItineraryItemType = ItineraryItemType.PLACE): List<ItineraryItemEntity>

    @Upsert
    suspend fun upsert(items: List<ItineraryItemEntity>)

    /** Soft delete (ADR-002): sets the tombstone and bumps `updated_at` in one write. */
    @Query("UPDATE itinerary_items SET deleted_at = :at, updated_at = :at WHERE id = :id")
    suspend fun softDelete(
        id: String,
        at: Instant,
    )

    /** Soft-deletes every live item of a trip (used when the trip itself is deleted). */
    @Query("UPDATE itinerary_items SET deleted_at = :at, updated_at = :at WHERE trip_id = :tripId AND deleted_at IS NULL")
    suspend fun softDeleteForTrip(
        tripId: String,
        at: Instant,
    )
}
