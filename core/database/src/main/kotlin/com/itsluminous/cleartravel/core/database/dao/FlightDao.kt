package com.itsluminous.cleartravel.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.itsluminous.cleartravel.core.database.entity.FlightJourneyEntity
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/** Flight journeys. All read queries exclude tombstoned rows (ADR-002). */
@Dao
interface FlightDao {
    @Query("SELECT * FROM flight_journeys WHERE deleted_at IS NULL AND archived = 0 ORDER BY date IS NULL, date, sched_dep")
    fun observeActive(): Flow<List<FlightJourneyEntity>>

    @Query("SELECT * FROM flight_journeys WHERE deleted_at IS NULL AND archived = 1 ORDER BY date DESC")
    fun observeArchived(): Flow<List<FlightJourneyEntity>>

    @Query("SELECT * FROM flight_journeys WHERE id = :id AND deleted_at IS NULL")
    fun observeById(id: String): Flow<FlightJourneyEntity?>

    @Query("SELECT * FROM flight_journeys WHERE id = :id AND deleted_at IS NULL")
    suspend fun getById(id: String): FlightJourneyEntity?

    @Upsert
    suspend fun upsert(flight: FlightJourneyEntity)

    /** Soft delete (ADR-002): sets the tombstone and bumps `updated_at` in one write. */
    @Query("UPDATE flight_journeys SET deleted_at = :at, updated_at = :at WHERE id = :id")
    suspend fun softDelete(
        id: String,
        at: Instant,
    )
}
