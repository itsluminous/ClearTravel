package com.itsluminous.cleartravel.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.itsluminous.cleartravel.core.database.entity.TripEntity
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/** Trips. All read queries exclude tombstoned rows (ADR-002). */
@Dao
interface TripDao {
    @Query("SELECT * FROM trips WHERE deleted_at IS NULL AND archived = 0 ORDER BY start_date IS NULL, start_date, name")
    fun observeActive(): Flow<List<TripEntity>>

    @Query("SELECT * FROM trips WHERE deleted_at IS NULL AND archived = 1 ORDER BY start_date DESC")
    fun observeArchived(): Flow<List<TripEntity>>

    @Query("SELECT * FROM trips WHERE id = :id AND deleted_at IS NULL")
    fun observeById(id: String): Flow<TripEntity?>

    @Query("SELECT * FROM trips WHERE id = :id AND deleted_at IS NULL")
    suspend fun getById(id: String): TripEntity?

    @Upsert
    suspend fun upsert(trip: TripEntity)

    /** Soft delete (ADR-002): sets the tombstone and bumps `updated_at` in one write. */
    @Query("UPDATE trips SET deleted_at = :at, updated_at = :at WHERE id = :id")
    suspend fun softDelete(
        id: String,
        at: Instant,
    )
}
