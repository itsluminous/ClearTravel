package com.itsluminous.cleartravel.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.itsluminous.cleartravel.core.database.entity.FlightJourneyEntity
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.LocalDate

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

    /**
     * Live (non-tombstoned, archived OR active) journey with this airline + flight
     * number + date — the duplicate guard of ADR-025. Inputs must already be
     * normalized (trimmed, upper-cased, flight number without leading zeros); the
     * stored values are normalized in SQL so legacy rows with stray spaces or
     * zero-padded numbers match too.
     */
    @Query(
        "SELECT * FROM flight_journeys WHERE deleted_at IS NULL " +
            "AND UPPER(TRIM(airline_iata)) = :airlineIata " +
            "AND LTRIM(UPPER(TRIM(flight_number)), '0') = :flightNumber " +
            "AND date = :date LIMIT 1",
    )
    suspend fun findLiveByFlight(
        airlineIata: String,
        flightNumber: String,
        date: LocalDate,
    ): FlightJourneyEntity?

    @Upsert
    suspend fun upsert(flight: FlightJourneyEntity)

    /** Soft delete (ADR-002): sets the tombstone and bumps `updated_at` in one write. */
    @Query("UPDATE flight_journeys SET deleted_at = :at, updated_at = :at WHERE id = :id")
    suspend fun softDelete(
        id: String,
        at: Instant,
    )
}
