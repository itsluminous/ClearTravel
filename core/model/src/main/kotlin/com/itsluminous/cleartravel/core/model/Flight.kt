package com.itsluminous.cleartravel.core.model

import java.time.Instant
import java.time.LocalDate

/**
 * A flight journey (one leg). Since ADR-046 the people on board live in
 * [FlightPassenger] rows; [seat] and [boardingPassPath] are kept as the PRIMARY
 * passenger's values (passenger #1 by `sortOrder`; the first pass found for the
 * path), mirrored by the repository on every passenger write so cards, share images
 * and backups from older builds keep reading what they always did. New code reads
 * the passengers.
 */
data class FlightJourney(
    override val id: String = EntityIds.newId(),
    /** Airline IATA code, e.g. "6E". */
    val airlineIata: String,
    /** Numeric flight number without the IATA prefix, e.g. "2345". */
    val flightNumber: String,
    /** Local departure date. */
    val date: LocalDate? = null,
    /** Booking-level record locator (the booking's PNR; per-passenger PNRs sit on the passengers). */
    val pnrBookingRef: String = "",
    /** Mirror of passenger #1's seat (ADR-046); empty when the flight has no passengers. */
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
    /** Local path of the stored boarding-pass file — mirror of the first passenger pass (ADR-046); null = none. */
    val boardingPassPath: String? = null,
    /** Airline web check-in deep link; null = unknown. */
    val checkInUrl: String? = null,
    /** Google Calendar event id when synced; null = never synced. */
    val googleEventId: String? = null,
    override val updatedAt: Instant = Instant.now(),
    override val deletedAt: Instant? = null,
) : SyncableEntity

/**
 * One person on a [FlightJourney] (ADR-046, schema v4): their seat, their own
 * boarding-pass file and the per-passenger booking data a pass carries. Two boarding
 * passes for the same flight are two passengers of ONE journey, never two journeys.
 */
data class FlightPassenger(
    override val id: String = EntityIds.newId(),
    val flightId: String,
    /** As printed on the pass/ticket, e.g. "BANDANA KUMARI" or "SHARMA/RAHUL". */
    val name: String = "",
    /** Seat, e.g. "12A"; empty until assigned. */
    val seat: String = "",
    /** This passenger's record locator when it differs from the booking's (often the same). */
    val pnrBookingRef: String = "",
    /** Local path of THIS passenger's stored boarding pass; null = none imported. */
    val boardingPassPath: String? = null,
    /** Boarding-pass check-in sequence number; empty when unknown. */
    val sequenceNumber: String = "",
    /** Cabin printed on the pass when it differs from the booking's; usually empty. */
    val cabinClass: String = "",
    /** Position in the passenger list; #0 is the primary passenger mirrored on the flight row. */
    val sortOrder: Int = 0,
    override val updatedAt: Instant = Instant.now(),
    override val deletedAt: Instant? = null,
) : SyncableEntity

/** A flight with its live passengers in `sortOrder` (ADR-046) — the shape lists and detail sheets read. */
data class FlightWithPassengers(
    val flight: FlightJourney,
    val passengers: List<FlightPassenger> = emptyList(),
) {
    /** Every stored boarding pass: the passengers' own, else the flight row's legacy mirror path. */
    val boardingPasses: List<FlightBoardingPass>
        get() {
            val own = passengers.mapNotNull { p -> p.boardingPassPath?.let { FlightBoardingPass(it, p) } }
            if (own.isNotEmpty()) return own
            return listOfNotNull(flight.boardingPassPath?.let { FlightBoardingPass(it, passenger = null) })
        }

    /** Non-blank seats in passenger order, e.g. `["12A", "12B"]`; falls back to the flight row's mirror. */
    val seats: List<String>
        get() = passengers.map { it.seat }.filter { it.isNotBlank() }.ifEmpty { listOfNotNull(flight.seat.takeIf { it.isNotBlank() }) }
}

/** One stored boarding pass of a flight: its file and the passenger it belongs to (null = legacy flight-level file). */
data class FlightBoardingPass(
    val path: String,
    val passenger: FlightPassenger?,
)

/** Derived ids for [FlightPassenger] rows the app creates on behalf of older data (ADR-046). */
object FlightPassengerIds {
    /**
     * The ONE passenger row backfilled for a flight that predates ADR-046 (schema v3
     * migration and v3-build backup imports both use it), derived from the flight id
     * so the two paths never produce two rows for the same legacy seat.
     */
    fun legacy(flightId: String): String =
        java.util.UUID.nameUUIDFromBytes("cleartravel:flight-passenger:legacy:$flightId".toByteArray(Charsets.UTF_8)).toString()
}

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
