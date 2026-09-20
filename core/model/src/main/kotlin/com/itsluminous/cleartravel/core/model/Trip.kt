package com.itsluminous.cleartravel.core.model

import java.time.Instant
import java.time.LocalDate

/**
 * A trip: the aggregate root of the Trips tab. Owns day-grouped [ItineraryItem]s and
 * may be referenced by [Checklist]s.
 */
data class Trip(
    override val id: String = EntityIds.newId(),
    val name: String,
    val destination: String = "",
    val startDate: LocalDate? = null,
    val endDate: LocalDate? = null,
    /** Cover emoji shown on the trip card (single grapheme, e.g. "🏔️"). */
    val coverEmoji: String = "",
    /** Cover color as an ARGB hex string, e.g. "#FF0B57D0"; empty = theme default. */
    val coverColor: String = "",
    val archived: Boolean = false,
    override val updatedAt: Instant = Instant.now(),
    override val deletedAt: Instant? = null,
) : SyncableEntity

/**
 * One entry in a trip's day-grouped itinerary: either a PLACE to visit or a COMMUTE
 * leg between places. Place-only fields ([latitude]/[longitude], [category]) and
 * commute-only fields ([commuteMode], [fromName], [toName], linked journey) are
 * nullable/defaulted so a single row type covers both.
 */
data class ItineraryItem(
    override val id: String = EntityIds.newId(),
    val tripId: String,
    /** 0-based day within the trip; pairs with [date] when the trip has dates. */
    val dayIndex: Int = 0,
    /** Concrete calendar date of the day, when known (kept in sync with [dayIndex]). */
    val date: LocalDate? = null,
    /** Position within the day (stable ordering for timeline + map polyline). */
    val orderInDay: Int = 0,
    val type: ItineraryItemType = ItineraryItemType.PLACE,
    val name: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
    /** Planned local time within the day, "HH:mm"; empty = unscheduled. */
    val plannedTime: String = "",
    /** Short note / fun facts field. */
    val note: String = "",
    val category: PlaceCategory = PlaceCategory.OTHER,
    /** Optional external link (booking page, article…). */
    val link: String = "",
    /** COMMUTE only: transport mode. */
    val commuteMode: CommuteMode = CommuteMode.OTHER,
    /** COMMUTE only: origin display name. */
    val fromName: String = "",
    /** COMMUTE only: destination display name. */
    val toName: String = "",
    /** COMMUTE only: id of a linked [TrainTicket]/[FlightJourney]; null = not linked. */
    val linkedJourneyId: String? = null,
    /** COMMUTE only: which aggregate [linkedJourneyId] points at. */
    val linkedJourneyType: JourneyType? = null,
    /** Google Calendar event id when synced; null = never synced. */
    val googleEventId: String? = null,
    override val updatedAt: Instant = Instant.now(),
    override val deletedAt: Instant? = null,
) : SyncableEntity
