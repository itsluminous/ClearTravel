package com.itsluminous.cleartravel.core.data.backup

import kotlinx.serialization.Serializable

/**
 * Wire format of a ClearTravel backup ZIP (ADR-015, `docs/backup-format.md`).
 *
 * These DTOs are DELIBERATELY decoupled from both the Room entities and the domain
 * models: the backup format is a frozen external contract that must stay stable even
 * when the schema or models evolve. Conventions match the database storage layer
 * (ADR-004): `Instant`s as epoch millis, `LocalDate`s as ISO-8601 strings, enums as
 * their stable `storageValue` strings (unknown values parse to safe fallbacks on
 * import, so newer-app backups degrade instead of crashing).
 */
@Serializable
data class BackupManifest(
    val schemaVersion: Int,
    val appVersion: String,
    /** Export wall-clock time, epoch millis. */
    val createdAt: Long,
    /** Row count per entity file name (e.g. `"trips" to 4`), tombstones included. */
    val entityCounts: Map<String, Int>,
) {
    val totalRows: Int get() = entityCounts.values.sum()

    companion object {
        /**
         * The schema version this app writes and the newest it can read.
         * 2 since ADR-031: the ZIP travels inside a password-derived portable
         * envelope (CTEB) and bundled files are plaintext INSIDE it. The inner layout
         * is unchanged, so v1 plain ZIPs still import (`docs/backup-format.md`).
         */
        const val SCHEMA_VERSION = 2
    }
}

@Serializable
data class TripDto(
    val id: String,
    val name: String,
    val destination: String = "",
    val startDate: String? = null,
    val endDate: String? = null,
    val coverEmoji: String = "",
    val coverColor: String = "",
    val archived: Boolean = false,
    val updatedAt: Long,
    val deletedAt: Long? = null,
)

@Serializable
data class ItineraryItemDto(
    val id: String,
    val tripId: String,
    val dayIndex: Int = 0,
    val date: String? = null,
    val orderInDay: Int = 0,
    val type: String,
    val name: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val plannedTime: String = "",
    val note: String = "",
    val category: String,
    val link: String = "",
    val commuteMode: String,
    val fromName: String = "",
    val toName: String = "",
    val linkedJourneyId: String? = null,
    val linkedJourneyType: String? = null,
    val googleEventId: String? = null,
    val updatedAt: Long,
    val deletedAt: Long? = null,
)

@Serializable
data class ChecklistDto(
    val id: String,
    val tripId: String? = null,
    val name: String,
    val updatedAt: Long,
    val deletedAt: Long? = null,
)

@Serializable
data class ChecklistItemDto(
    val id: String,
    val checklistId: String,
    val text: String,
    val checked: Boolean = false,
    val sortOrder: Int = 0,
    val updatedAt: Long,
    val deletedAt: Long? = null,
)

@Serializable
data class ChecklistPresetDto(
    val id: String,
    val name: String,
    val builtIn: Boolean = false,
    val updatedAt: Long,
    val deletedAt: Long? = null,
)

@Serializable
data class ChecklistPresetItemDto(
    val id: String,
    val presetId: String,
    val text: String,
    val sortOrder: Int = 0,
    val updatedAt: Long,
    val deletedAt: Long? = null,
)

@Serializable
data class TrainTicketDto(
    val id: String,
    val pnr: String,
    val trainNumber: String = "",
    val trainName: String = "",
    val journeyDate: String? = null,
    val fromStation: String = "",
    val toStation: String = "",
    val travelClass: String = "",
    val quota: String = "",
    val archived: Boolean = false,
    val googleEventId: String? = null,
    val lastFetchedAt: Long? = null,
    val updatedAt: Long,
    val deletedAt: Long? = null,
)

@Serializable
data class TrainPassengerDto(
    val id: String,
    val ticketId: String,
    val name: String = "",
    val coach: String = "",
    val seatBerth: String = "",
    val bookingStatus: String = "",
    val currentStatus: String = "",
    val sortOrder: Int = 0,
    val updatedAt: Long,
    val deletedAt: Long? = null,
)

@Serializable
data class TrainRouteStopDto(
    val id: String,
    val ticketId: String,
    val stationName: String,
    val arrival: String = "",
    val departure: String = "",
    val platform: String = "",
    val day: Int = 1,
    val sortOrder: Int = 0,
    val updatedAt: Long,
    val deletedAt: Long? = null,
)

/** Added in ADR-022 (additive — readers of older backups see an empty list). */
@Serializable
data class TrainCoachDto(
    val id: String,
    val ticketId: String,
    val code: String,
    val sortOrder: Int = 0,
    val updatedAt: Long,
    val deletedAt: Long? = null,
)

@Serializable
data class FlightJourneyDto(
    val id: String,
    val airlineIata: String,
    val flightNumber: String,
    val date: String? = null,
    val pnrBookingRef: String = "",
    val seat: String = "",
    val cabinClass: String = "",
    val depAirport: String = "",
    val arrAirport: String = "",
    val schedDep: Long? = null,
    val schedArr: Long? = null,
    val estDep: Long? = null,
    val estArr: Long? = null,
    val status: String,
    val depTerminal: String = "",
    val depGate: String = "",
    val arrTerminal: String = "",
    val arrGate: String = "",
    val baggageBelt: String = "",
    val aircraftType: String = "",
    val archived: Boolean = false,
    val lastFetchedAt: Long? = null,
    val boardingPassPath: String? = null,
    val checkInUrl: String? = null,
    val googleEventId: String? = null,
    /**
     * ADR-038: true when the boarding-pass file's bytes are bundled in the ZIP under
     * `boarding_passes/<flightId>` — whenever the file existed at export time,
     * regardless of any Drive mirror row (the local backup must be self-contained).
     * Absent in older backups (= false): the path is restored as recorded.
     */
    val boardingPassBundled: Boolean = false,
    val updatedAt: Long,
    val deletedAt: Long? = null,
)

@Serializable
data class AttachmentDto(
    val id: String,
    val ownerType: String,
    val ownerId: String,
    val localPath: String,
    val driveFileId: String? = null,
    val mimeType: String = "",
    /**
     * True when the attachment's file bytes are bundled in the ZIP under
     * `attachments/<id>` — only ever true for local-only rows (`driveFileId == null`)
     * whose file existed at export time. Drive-backed rows carry only [driveFileId];
     * the Google milestone re-fetches them on restore (documented seam, ADR-015).
     */
    val bundled: Boolean = false,
    val updatedAt: Long,
    val deletedAt: Long? = null,
)

/**
 * Added in ADR-027 (additive — readers of older backups see an empty list). File bytes
 * follow the attachment rule: bundled under `attachments/<id>` whenever the row is
 * local-only (`driveFileId == null` — every document today) and the file exists.
 */
@Serializable
data class TravelDocumentDto(
    val id: String,
    val name: String,
    val type: String,
    val filePath: String,
    val mimeType: String = "",
    val addedAt: Long,
    val expiryDate: String? = null,
    val note: String = "",
    val driveFileId: String? = null,
    /** True when the file bytes are bundled in the ZIP under `attachments/<id>`. */
    val bundled: Boolean = false,
    val updatedAt: Long,
    val deletedAt: Long? = null,
)

/** Everything a backup carries besides attachment file bytes. */
data class BackupSnapshot(
    val manifest: BackupManifest,
    val trips: List<TripDto> = emptyList(),
    val itineraryItems: List<ItineraryItemDto> = emptyList(),
    val checklists: List<ChecklistDto> = emptyList(),
    val checklistItems: List<ChecklistItemDto> = emptyList(),
    val checklistPresets: List<ChecklistPresetDto> = emptyList(),
    val checklistPresetItems: List<ChecklistPresetItemDto> = emptyList(),
    val trainTickets: List<TrainTicketDto> = emptyList(),
    val trainPassengers: List<TrainPassengerDto> = emptyList(),
    val trainRouteStops: List<TrainRouteStopDto> = emptyList(),
    val trainCoaches: List<TrainCoachDto> = emptyList(),
    val flightJourneys: List<FlightJourneyDto> = emptyList(),
    val attachments: List<AttachmentDto> = emptyList(),
    val travelDocuments: List<TravelDocumentDto> = emptyList(),
)

/** ZIP entry names — the frozen file layout of a backup (ADR-015). */
object BackupEntries {
    const val MANIFEST = "manifest.json"
    const val TRIPS = "entities/trips.json"
    const val ITINERARY_ITEMS = "entities/itinerary_items.json"
    const val CHECKLISTS = "entities/checklists.json"
    const val CHECKLIST_ITEMS = "entities/checklist_items.json"
    const val CHECKLIST_PRESETS = "entities/checklist_presets.json"
    const val CHECKLIST_PRESET_ITEMS = "entities/checklist_preset_items.json"
    const val TRAIN_TICKETS = "entities/train_tickets.json"
    const val TRAIN_PASSENGERS = "entities/train_passengers.json"
    const val TRAIN_ROUTE_STOPS = "entities/train_route_stops.json"
    const val TRAIN_COACHES = "entities/train_coaches.json"
    const val FLIGHT_JOURNEYS = "entities/flight_journeys.json"
    const val ATTACHMENTS = "entities/attachments.json"
    const val TRAVEL_DOCUMENTS = "entities/travel_documents.json"
    const val ATTACHMENT_DIR = "attachments/"

    /** ADR-038: bundled boarding-pass bytes, keyed by flight id (older readers ignore the directory). */
    const val BOARDING_PASS_DIR = "boarding_passes/"

    fun attachmentEntry(attachmentId: String): String = ATTACHMENT_DIR + attachmentId

    fun boardingPassEntry(flightId: String): String = BOARDING_PASS_DIR + flightId

    /** Manifest count keys, one per entity file. */
    const val KEY_TRIPS = "trips"
    const val KEY_ITINERARY_ITEMS = "itinerary_items"
    const val KEY_CHECKLISTS = "checklists"
    const val KEY_CHECKLIST_ITEMS = "checklist_items"
    const val KEY_CHECKLIST_PRESETS = "checklist_presets"
    const val KEY_CHECKLIST_PRESET_ITEMS = "checklist_preset_items"
    const val KEY_TRAIN_TICKETS = "train_tickets"
    const val KEY_TRAIN_PASSENGERS = "train_passengers"
    const val KEY_TRAIN_ROUTE_STOPS = "train_route_stops"
    const val KEY_TRAIN_COACHES = "train_coaches"
    const val KEY_FLIGHT_JOURNEYS = "flight_journeys"
    const val KEY_ATTACHMENTS = "attachments"
    const val KEY_TRAVEL_DOCUMENTS = "travel_documents"
}
