package com.itsluminous.cleartravel.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.itsluminous.cleartravel.core.database.entity.TrainCoachEntity
import com.itsluminous.cleartravel.core.database.entity.TrainPassengerEntity
import com.itsluminous.cleartravel.core.database.entity.TrainRouteStopEntity
import com.itsluminous.cleartravel.core.database.entity.TrainTicketEntity
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/** Train tickets + passengers + route stops + coaches (ADR-022). Read queries exclude tombstones (ADR-002). */
@Dao
interface TrainDao {
    @Query("SELECT * FROM train_tickets WHERE deleted_at IS NULL AND archived = 0 ORDER BY journey_date IS NULL, journey_date")
    fun observeActive(): Flow<List<TrainTicketEntity>>

    @Query("SELECT * FROM train_tickets WHERE deleted_at IS NULL AND archived = 1 ORDER BY journey_date DESC")
    fun observeArchived(): Flow<List<TrainTicketEntity>>

    @Query("SELECT * FROM train_tickets WHERE id = :id AND deleted_at IS NULL")
    fun observeById(id: String): Flow<TrainTicketEntity?>

    @Query("SELECT * FROM train_tickets WHERE id = :id AND deleted_at IS NULL")
    suspend fun getById(id: String): TrainTicketEntity?

    /**
     * Live (non-tombstoned, archived OR active) ticket with this PNR — the duplicate
     * guard of ADR-024. [pnr] must already be normalized (trimmed, upper-cased);
     * the stored value is normalized in SQL so legacy rows with stray spaces match.
     */
    @Query("SELECT * FROM train_tickets WHERE deleted_at IS NULL AND UPPER(TRIM(pnr)) = :pnr LIMIT 1")
    suspend fun findLiveByPnr(pnr: String): TrainTicketEntity?

    @Query("SELECT * FROM train_passengers WHERE ticket_id = :ticketId AND deleted_at IS NULL ORDER BY sort_order")
    fun observePassengers(ticketId: String): Flow<List<TrainPassengerEntity>>

    @Query("SELECT * FROM train_passengers WHERE ticket_id = :ticketId AND deleted_at IS NULL ORDER BY sort_order")
    suspend fun getPassengers(ticketId: String): List<TrainPassengerEntity>

    @Query("SELECT * FROM train_route_stops WHERE ticket_id = :ticketId AND deleted_at IS NULL ORDER BY sort_order")
    fun observeRouteStops(ticketId: String): Flow<List<TrainRouteStopEntity>>

    @Query("SELECT * FROM train_coaches WHERE ticket_id = :ticketId AND deleted_at IS NULL ORDER BY sort_order")
    fun observeCoaches(ticketId: String): Flow<List<TrainCoachEntity>>

    @Upsert
    suspend fun upsert(ticket: TrainTicketEntity)

    @Upsert
    suspend fun upsertPassengers(passengers: List<TrainPassengerEntity>)

    @Upsert
    suspend fun upsertRouteStops(stops: List<TrainRouteStopEntity>)

    @Upsert
    suspend fun upsertCoaches(coaches: List<TrainCoachEntity>)

    /** Soft delete (ADR-002): sets the tombstone and bumps `updated_at` in one write. */
    @Query("UPDATE train_tickets SET deleted_at = :at, updated_at = :at WHERE id = :id")
    suspend fun softDelete(
        id: String,
        at: Instant,
    )

    @Query("UPDATE train_passengers SET deleted_at = :at, updated_at = :at WHERE id = :id")
    suspend fun softDeletePassenger(
        id: String,
        at: Instant,
    )

    /** Soft-deletes every live passenger of a ticket (used when the ticket is deleted). */
    @Query("UPDATE train_passengers SET deleted_at = :at, updated_at = :at WHERE ticket_id = :ticketId AND deleted_at IS NULL")
    suspend fun softDeletePassengersFor(
        ticketId: String,
        at: Instant,
    )

    /** Soft-deletes every live route stop of a ticket (delete cascade + route refresh). */
    @Query("UPDATE train_route_stops SET deleted_at = :at, updated_at = :at WHERE ticket_id = :ticketId AND deleted_at IS NULL")
    suspend fun softDeleteRouteStopsFor(
        ticketId: String,
        at: Instant,
    )

    /** Soft-deletes every live coach of a ticket (delete cascade + coach refresh, ADR-022). */
    @Query("UPDATE train_coaches SET deleted_at = :at, updated_at = :at WHERE ticket_id = :ticketId AND deleted_at IS NULL")
    suspend fun softDeleteCoachesFor(
        ticketId: String,
        at: Instant,
    )
}
