package com.itsluminous.cleartravel.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.itsluminous.cleartravel.core.database.entity.ItineraryItemEntity
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
