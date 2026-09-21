package com.itsluminous.cleartravel.core.data.repository

import com.itsluminous.cleartravel.core.data.provider.FlightStatusResult
import com.itsluminous.cleartravel.core.data.provider.TrainStatusResult
import com.itsluminous.cleartravel.core.model.Attachment
import com.itsluminous.cleartravel.core.model.AttachmentOwnerType
import com.itsluminous.cleartravel.core.model.FlightJourney
import com.itsluminous.cleartravel.core.model.TrainCoach
import com.itsluminous.cleartravel.core.model.TrainPassenger
import com.itsluminous.cleartravel.core.model.TrainRouteStop
import com.itsluminous.cleartravel.core.model.TrainTicket
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/**
 * How a flight is identified for de-duplication (ADR-025): airline IATA + flight
 * number + local date. Shared by [FlightRepository] implementations (production and
 * test fakes) so every lookup normalizes exactly the same way.
 */
object FlightIdentity {
    /** Trimmed, upper-cased airline code ("ai " → "AI"). */
    fun normalizeAirline(raw: String): String = raw.trim().uppercase()

    /**
     * Trimmed, upper-cased flight number without leading zeros ("0101" → "101",
     * " 2345a" → "2345A"). BCBP barcodes strip leading zeros while manual entry and
     * some OCR captures keep them; both must denote the same flight.
     */
    fun normalizeFlightNumber(raw: String): String = raw.trim().uppercase().trimStart('0')
}

/** Train tickets aggregate: ticket + passengers + route stops (ADR-004) + coaches (ADR-022). */
interface TrainRepository {
    fun observeActive(): Flow<List<TrainTicket>>

    fun observeArchived(): Flow<List<TrainTicket>>

    fun observeTicket(id: String): Flow<TrainTicket?>

    suspend fun getTicket(id: String): TrainTicket?

    /**
     * Live (non-tombstoned) ticket — archived included — whose PNR equals [pnr]
     * after trimming/case-folding, or null. Duplicate guard for every add path
     * (ADR-024); a tombstoned ticket's PNR is free to be re-added.
     */
    suspend fun findByPnr(pnr: String): TrainTicket?

    /** Live passengers ordered by `sortOrder`. */
    fun observePassengers(ticketId: String): Flow<List<TrainPassenger>>

    /** Live route stops ordered by `sortOrder`. */
    fun observeRouteStops(ticketId: String): Flow<List<TrainRouteStop>>

    /** Live coach composition ordered by `sortOrder` (engine first), ADR-022. */
    fun observeCoaches(ticketId: String): Flow<List<TrainCoach>>

    /** Upserts [ticket] with a bumped `updatedAt`; returns the stored copy. */
    suspend fun save(ticket: TrainTicket): TrainTicket

    /** Bulk upsert of passengers; all rows get the same bumped `updatedAt`. */
    suspend fun savePassengers(passengers: List<TrainPassenger>): List<TrainPassenger>

    /** Replaces the stored route: soft-deletes existing stops, inserts [stops]. */
    suspend fun replaceRouteStops(
        ticketId: String,
        stops: List<TrainRouteStop>,
    ): List<TrainRouteStop>

    /** Replaces the stored coach composition: soft-deletes existing, inserts [coaches] (ADR-022). */
    suspend fun replaceCoaches(
        ticketId: String,
        coaches: List<TrainCoach>,
    ): List<TrainCoach>

    /**
     * Writes a provider fetch (ADR-005) into Room: per-passenger `currentStatus`
     * (matched by position; coach/seatBerth merged when non-empty) and the ticket's
     * `lastFetchedAt`. No-op for a ticket id that doesn't exist.
     */
    suspend fun applyStatusResult(
        ticketId: String,
        result: TrainStatusResult,
    )

    suspend fun setArchived(
        id: String,
        archived: Boolean,
    )

    /** Soft-deletes the ticket AND its passengers, route stops, coaches, and attachments. */
    suspend fun delete(id: String)
}

/** Flight journeys aggregate (ADR-004). */
interface FlightRepository {
    fun observeActive(): Flow<List<FlightJourney>>

    fun observeArchived(): Flow<List<FlightJourney>>

    fun observeFlight(id: String): Flow<FlightJourney?>

    suspend fun getFlight(id: String): FlightJourney?

    /**
     * Live (non-tombstoned) journey — archived included — flying [airlineIata]
     * [flightNumber] on [date], or null. Inputs are normalized per [FlightIdentity]
     * (airline trimmed + case-folded; flight number trimmed, case-folded, leading
     * zeros dropped so a BCBP-stripped "101" matches a hand-typed "0101"). Duplicate
     * guard for every add path (ADR-025); a tombstoned journey frees its slot.
     */
    suspend fun findByFlight(
        airlineIata: String,
        flightNumber: String,
        date: LocalDate,
    ): FlightJourney?

    /** Upserts [flight] with a bumped `updatedAt`; returns the stored copy. */
    suspend fun save(flight: FlightJourney): FlightJourney

    /**
     * Writes a provider fetch (ADR-005) into Room: status always, times/gates/
     * terminals/belt/aircraft only when the result reports them (non-null/non-empty),
     * plus `lastFetchedAt`. No-op for a flight id that doesn't exist.
     */
    suspend fun applyStatusResult(
        flightId: String,
        result: FlightStatusResult,
    )

    suspend fun setArchived(
        id: String,
        archived: Boolean,
    )

    /** Soft-deletes the flight AND its attachments. */
    suspend fun delete(id: String)
}

/** File attachments aggregate — polymorphic owner (ADR-004). */
interface AttachmentRepository {
    fun observeForOwner(
        ownerType: AttachmentOwnerType,
        ownerId: String,
    ): Flow<List<Attachment>>

    suspend fun getAttachment(id: String): Attachment?

    /** Live attachments not yet uploaded to Drive (upload queue candidates). */
    suspend fun getPendingDriveUploads(): List<Attachment>

    /** Upserts [attachment] with a bumped `updatedAt`; returns the stored copy. */
    suspend fun save(attachment: Attachment): Attachment

    suspend fun delete(id: String)

    suspend fun deleteForOwner(
        ownerType: AttachmentOwnerType,
        ownerId: String,
    )
}
