package com.itsluminous.cleartravel.core.model

import java.time.Instant
import java.time.LocalDate

/** A flight journey (one leg). */
data class FlightJourney(
    override val id: String = EntityIds.newId(),
    /** Airline IATA code, e.g. "6E". */
    val airlineIata: String,
    /** Numeric flight number without the IATA prefix, e.g. "2345". */
    val flightNumber: String,
    /** Local departure date. */
    val date: LocalDate? = null,
    val pnrBookingRef: String = "",
    val seat: String = "",
    val cabinClass: String = "",
    /** Departure airport IATA code, e.g. "BLR". */
    val depAirport: String = "",
    /** Arrival airport IATA code, e.g. "DEL". */
    val arrAirport: String = "",
    val schedDep: Instant? = null,
    val schedArr: Instant? = null,
    val estDep: Instant? = null,
    val estArr: Instant? = null,
    val status: FlightStatus = FlightStatus.SCHEDULED,
    val depTerminal: String = "",
    val depGate: String = "",
    val arrTerminal: String = "",
    val arrGate: String = "",
    val baggageBelt: String = "",
    val aircraftType: String = "",
    val archived: Boolean = false,
    /** When status was last fetched from a provider; null = never. */
    val lastFetchedAt: Instant? = null,
    /** Local path of the stored boarding-pass file; null = none imported. */
    val boardingPassPath: String? = null,
    /** Airline web check-in deep link; null = unknown. */
    val checkInUrl: String? = null,
    /** Google Calendar event id when synced; null = never synced. */
    val googleEventId: String? = null,
    override val updatedAt: Instant = Instant.now(),
    override val deletedAt: Instant? = null,
) : SyncableEntity

/**
 * A file attached to a train ticket, flight journey, or itinerary item (ticket PDFs,
 * boarding passes, images). The local copy is the primary offline source; [driveFileId]
 * is set once uploaded to Google Drive.
 */
data class Attachment(
    override val id: String = EntityIds.newId(),
    val ownerType: AttachmentOwnerType,
    /** Id of the owning [TrainTicket]/[FlightJourney]/[ItineraryItem]. */
    val ownerId: String,
    val localPath: String,
    /** Google Drive file id once uploaded; null = local-only. */
    val driveFileId: String? = null,
    val mimeType: String = "",
    override val updatedAt: Instant = Instant.now(),
    override val deletedAt: Instant? = null,
) : SyncableEntity
