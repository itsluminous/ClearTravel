package com.itsluminous.cleartravel.core.data.share

import com.itsluminous.cleartravel.core.model.Checklist
import com.itsluminous.cleartravel.core.model.ChecklistItem
import com.itsluminous.cleartravel.core.model.CommuteMode
import com.itsluminous.cleartravel.core.model.FlightJourney
import com.itsluminous.cleartravel.core.model.ItineraryItem
import com.itsluminous.cleartravel.core.model.ItineraryItemType
import com.itsluminous.cleartravel.core.model.PlaceCategory
import com.itsluminous.cleartravel.core.model.Trip
import java.time.Instant
import java.time.LocalDate

/**
 * ADR-039 model ↔ payload mappers. PURE: no clock, no I/O. Device-local fields are
 * stripped on the way out ([ItineraryItem.linkedJourneyId]/[ItineraryItem.linkedJourneyType],
 * `googleEventId`, `archived`, timestamps) and left to the importer on the way in.
 */
object SharePayloadMappers {
    fun toPayload(
        trip: Trip,
        items: List<ItineraryItem>,
    ): TripSharePayload =
        TripSharePayload(
            version = ShareLinkCodec.SUPPORTED_VERSION,
            id = trip.id,
            name = trip.name,
            destination = trip.destination,
            startDate = trip.startDate?.toString(),
            endDate = trip.endDate?.toString(),
            coverEmoji = trip.coverEmoji,
            coverColor = trip.coverColor,
            items =
                items
                    .sortedWith(compareBy({ it.dayIndex }, { it.orderInDay }))
                    .map { item ->
                        SharedItineraryItem(
                            id = item.id,
                            dayIndex = item.dayIndex,
                            date = item.date?.toString(),
                            orderInDay = item.orderInDay,
                            type = item.type.storageValue,
                            name = item.name,
                            latitude = item.latitude,
                            longitude = item.longitude,
                            plannedTime = item.plannedTime,
                            note = item.note,
                            category = item.category.storageValue,
                            link = item.link,
                            commuteMode = item.commuteMode.storageValue,
                            fromName = item.fromName,
                            toName = item.toName,
                        )
                    },
        )

    fun toPayload(
        checklist: Checklist,
        items: List<ChecklistItem>,
    ): ChecklistSharePayload =
        ChecklistSharePayload(
            version = ShareLinkCodec.SUPPORTED_VERSION,
            id = checklist.id,
            name = checklist.name,
            tripId = checklist.tripId,
            items =
                items
                    .sortedBy { it.sortOrder }
                    .map { SharedChecklistItem(id = it.id, text = it.text, checked = it.checked, sortOrder = it.sortOrder) },
        )

    fun toPayload(flight: FlightJourney): FlightSharePayload =
        FlightSharePayload(
            version = ShareLinkCodec.SUPPORTED_VERSION,
            airlineIata = flight.airlineIata,
            flightNumber = flight.flightNumber,
            date = flight.date?.toString(),
            depAirport = flight.depAirport,
            arrAirport = flight.arrAirport,
            schedDep = flight.schedDep?.epochSecond,
            schedArr = flight.schedArr?.epochSecond,
            depTerminal = flight.depTerminal,
            arrTerminal = flight.arrTerminal,
        )

    /**
     * The trip row for [payload]. [existing] (same id on the recipient) keeps its
     * device-local `archived` flag; everything shared overwrites.
     */
    fun toTrip(
        payload: TripSharePayload,
        existing: Trip?,
    ): Trip =
        Trip(
            id = payload.id,
            name = payload.name.trim(),
            destination = payload.destination,
            startDate = parseDate(payload.startDate),
            endDate = parseDate(payload.endDate),
            coverEmoji = payload.coverEmoji,
            coverColor = payload.coverColor,
            archived = existing?.archived ?: false,
        )

    /**
     * The item rows for [payload]. An item whose id already exists on the recipient
     * ([existing], keyed by id) keeps its device-local journey link and calendar id —
     * the recipient's own linkage must survive a re-share (ADR-039).
     */
    fun toItineraryItems(
        payload: TripSharePayload,
        existing: Map<String, ItineraryItem>,
    ): List<ItineraryItem> =
        payload.items.map { shared ->
            val local = existing[shared.id]
            ItineraryItem(
                id = shared.id,
                tripId = payload.id,
                dayIndex = shared.dayIndex,
                date = parseDate(shared.date),
                orderInDay = shared.orderInDay,
                type = ItineraryItemType.fromStorage(shared.type),
                name = shared.name.trim(),
                latitude = shared.latitude,
                longitude = shared.longitude,
                plannedTime = shared.plannedTime,
                note = shared.note,
                category = PlaceCategory.fromStorage(shared.category),
                link = shared.link,
                commuteMode = CommuteMode.fromStorage(shared.commuteMode),
                fromName = shared.fromName,
                toName = shared.toName,
                linkedJourneyId = local?.linkedJourneyId,
                linkedJourneyType = local?.linkedJourneyType,
                googleEventId = local?.googleEventId,
            )
        }

    /** The checklist row; [tripId] is the importer's resolved owner (payload trip present locally, or null). */
    fun toChecklist(
        payload: ChecklistSharePayload,
        tripId: String?,
    ): Checklist = Checklist(id = payload.id, tripId = tripId, name = payload.name.trim())

    /** Item rows with the SHARED checked state — the sharer's ticks replace the recipient's. */
    fun toChecklistItems(payload: ChecklistSharePayload): List<ChecklistItem> =
        payload.items.map {
            ChecklistItem(id = it.id, checklistId = payload.id, text = it.text.trim(), checked = it.checked, sortOrder = it.sortOrder)
        }

    fun parseDate(value: String?): LocalDate? = value?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    fun toInstant(epochSecond: Long?): Instant? = epochSecond?.let { runCatching { Instant.ofEpochSecond(it) }.getOrNull() }
}
