package com.itsluminous.cleartravel.core.data.share

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * ADR-039: the self-contained content a share link carries. Every payload is a
 * plain kotlinx-serialization DTO with a mandatory format version [version] (the only
 * field that is never omitted — see [ShareLinkCodec.json]); everything else is
 * defaulted so blank/absent values cost no bytes on the wire.
 *
 * Wire discipline: dates are ISO-8601 `LocalDate` strings, instants are epoch
 * SECONDS, enums are their `storageValue` strings (forward tolerant: unknown values
 * fall back through the model's `fromStorage`). Ids are the ORIGINAL entity UUIDs so
 * re-sharing UPDATES the recipient's copy instead of duplicating it.
 */
sealed interface SharePayload {
    val version: Int
    val kind: ShareKind
}

/** Which aggregate a share link carries; [pathSegment] is the URL's `<kind>` part. */
enum class ShareKind(
    val pathSegment: String,
) {
    TRIP("trip"),
    CHECKLIST("checklist"),
    FLIGHT("flight"),
    ;

    companion object {
        fun fromPathSegment(value: String): ShareKind? = entries.firstOrNull { it.pathSegment == value.lowercase() }
    }
}

/** A trip with its itinerary (commute legs WITHOUT their device-local journey links). */
@Serializable
data class TripSharePayload(
    @SerialName("v") override val version: Int,
    val id: String,
    val name: String,
    val destination: String = "",
    /** ISO-8601 local date, e.g. `2026-09-24`; null = undated trip. */
    val startDate: String? = null,
    val endDate: String? = null,
    val coverEmoji: String = "",
    val coverColor: String = "",
    val items: List<SharedItineraryItem> = emptyList(),
) : SharePayload {
    override val kind: ShareKind get() = ShareKind.TRIP
}

/**
 * One itinerary entry. Carries no `linkedJourneyId`/`linkedJourneyType` (the
 * recipient's journeys are their own — ADR-039) and no `googleEventId`.
 */
@Serializable
data class SharedItineraryItem(
    val id: String,
    val dayIndex: Int = 0,
    val date: String? = null,
    val orderInDay: Int = 0,
    /** `ItineraryItemType.storageValue`. */
    val type: String = "place",
    val name: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val plannedTime: String = "",
    val note: String = "",
    /** `PlaceCategory.storageValue`. */
    val category: String = "other",
    val link: String = "",
    /** `CommuteMode.storageValue`. */
    val commuteMode: String = "other",
    val fromName: String = "",
    val toName: String = "",
)

/** A checklist with its items INCLUDING their checked state (user requirement, ADR-039). */
@Serializable
data class ChecklistSharePayload(
    @SerialName("v") override val version: Int,
    val id: String,
    val name: String,
    /** Owning trip id when the checklist is trip-scoped; resolved against the recipient's trips on import. */
    val tripId: String? = null,
    val items: List<SharedChecklistItem> = emptyList(),
) : SharePayload {
    override val kind: ShareKind get() = ShareKind.CHECKLIST
}

@Serializable
data class SharedChecklistItem(
    val id: String,
    val text: String,
    val checked: Boolean = false,
    val sortOrder: Int = 0,
)

/**
 * The ADD data of a flight (ADR-039 part A): what the recipient needs to add the same
 * flight to their Journeys. Deliberately WITHOUT the sharer's booking reference, seat
 * and cabin — those are personal; the recipient fills their own in the prefilled form.
 * Also no id: a flight is not upserted, it goes through the add form (and its ADR-025
 * duplicate guard) like every other add path.
 */
@Serializable
data class FlightSharePayload(
    @SerialName("v") override val version: Int,
    val airlineIata: String,
    val flightNumber: String,
    /** ISO-8601 local departure date. */
    val date: String? = null,
    val depAirport: String = "",
    val arrAirport: String = "",
    /** Epoch seconds. */
    val schedDep: Long? = null,
    val schedArr: Long? = null,
    val depTerminal: String = "",
    val arrTerminal: String = "",
) : SharePayload {
    override val kind: ShareKind get() = ShareKind.FLIGHT
}
