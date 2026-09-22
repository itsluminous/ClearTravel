package com.itsluminous.cleartravel.core.testing

import com.itsluminous.cleartravel.core.model.Attachment
import com.itsluminous.cleartravel.core.model.AttachmentOwnerType
import com.itsluminous.cleartravel.core.model.Checklist
import com.itsluminous.cleartravel.core.model.ChecklistItem
import com.itsluminous.cleartravel.core.model.ChecklistPreset
import com.itsluminous.cleartravel.core.model.ChecklistPresetItem
import com.itsluminous.cleartravel.core.model.CommuteMode
import com.itsluminous.cleartravel.core.model.EntityIds
import com.itsluminous.cleartravel.core.model.FlightJourney
import com.itsluminous.cleartravel.core.model.FlightStatus
import com.itsluminous.cleartravel.core.model.ItineraryItem
import com.itsluminous.cleartravel.core.model.ItineraryItemType
import com.itsluminous.cleartravel.core.model.JourneyType
import com.itsluminous.cleartravel.core.model.PlaceCategory
import com.itsluminous.cleartravel.core.model.TrainCoach
import com.itsluminous.cleartravel.core.model.TrainPassenger
import com.itsluminous.cleartravel.core.model.TrainRouteStop
import com.itsluminous.cleartravel.core.model.TrainTicket
import com.itsluminous.cleartravel.core.model.TravelDocument
import com.itsluminous.cleartravel.core.model.TravelDocumentType
import com.itsluminous.cleartravel.core.model.Trip
import java.time.Instant
import java.time.LocalDate

/**
 * Fixture builders for domain models: every field gets a sensible default so tests
 * override only what they assert on. Fixture names are intentionally not user-visible
 * strings — fixtures never reach the UI.
 */
object Fixtures {
    /** Deterministic id for the singleton "current test row" — stable across runs. */
    const val FIXED_ID = "00000000-0000-0000-0000-0000c1ea51de"

    /** Deterministic clock for `updatedAt`/`createdAt` fields. */
    val NOW: Instant = Instant.parse("2026-09-20T09:00:00Z")

    /** Deterministic journey/trip date matching [NOW]. */
    val TODAY: LocalDate = LocalDate.parse("2026-09-20")

    fun trip(
        id: String = EntityIds.newId(),
        name: String = "Fixture Trip",
        destination: String = "Fixture City",
        startDate: LocalDate? = TODAY,
        endDate: LocalDate? = TODAY.plusDays(4),
        coverEmoji: String = "🏔️",
        coverColor: String = "#FF0B57D0",
        archived: Boolean = false,
        updatedAt: Instant = NOW,
        deletedAt: Instant? = null,
    ): Trip = Trip(id, name, destination, startDate, endDate, coverEmoji, coverColor, archived, updatedAt, deletedAt)

    fun itineraryItem(
        id: String = EntityIds.newId(),
        tripId: String = FIXED_ID,
        dayIndex: Int = 0,
        date: LocalDate? = TODAY,
        orderInDay: Int = 0,
        type: ItineraryItemType = ItineraryItemType.PLACE,
        name: String = "Fixture Place",
        latitude: Double? = 12.9716,
        longitude: Double? = 77.5946,
        plannedTime: String = "10:00",
        note: String = "",
        category: PlaceCategory = PlaceCategory.SIGHT,
        link: String = "",
        commuteMode: CommuteMode = CommuteMode.OTHER,
        fromName: String = "",
        toName: String = "",
        linkedJourneyId: String? = null,
        linkedJourneyType: JourneyType? = null,
        googleEventId: String? = null,
        updatedAt: Instant = NOW,
        deletedAt: Instant? = null,
    ): ItineraryItem =
        ItineraryItem(
            id,
            tripId,
            dayIndex,
            date,
            orderInDay,
            type,
            name,
            latitude,
            longitude,
            plannedTime,
            note,
            category,
            link,
            commuteMode,
            fromName,
            toName,
            linkedJourneyId,
            linkedJourneyType,
            googleEventId,
            updatedAt,
            deletedAt,
        )

    fun checklist(
        id: String = EntityIds.newId(),
        tripId: String? = null,
        name: String = "Fixture Checklist",
        updatedAt: Instant = NOW,
        deletedAt: Instant? = null,
    ): Checklist = Checklist(id, tripId, name, updatedAt, deletedAt)

    fun checklistItem(
        id: String = EntityIds.newId(),
        checklistId: String = FIXED_ID,
        text: String = "Fixture item",
        checked: Boolean = false,
        sortOrder: Int = 0,
        updatedAt: Instant = NOW,
        deletedAt: Instant? = null,
    ): ChecklistItem = ChecklistItem(id, checklistId, text, checked, sortOrder, updatedAt, deletedAt)

    fun checklistPreset(
        id: String = EntityIds.newId(),
        name: String = "Fixture Preset",
        builtIn: Boolean = false,
        updatedAt: Instant = NOW,
        deletedAt: Instant? = null,
    ): ChecklistPreset = ChecklistPreset(id, name, builtIn, updatedAt, deletedAt)

    fun checklistPresetItem(
        id: String = EntityIds.newId(),
        presetId: String = FIXED_ID,
        text: String = "Fixture preset item",
        sortOrder: Int = 0,
        updatedAt: Instant = NOW,
        deletedAt: Instant? = null,
    ): ChecklistPresetItem = ChecklistPresetItem(id, presetId, text, sortOrder, updatedAt, deletedAt)

    fun trainTicket(
        id: String = EntityIds.newId(),
        pnr: String = "1234567890",
        trainNumber: String = "12627",
        trainName: String = "Karnataka Express",
        journeyDate: LocalDate? = TODAY,
        fromStation: String = "SBC",
        toStation: String = "NDLS",
        travelClass: String = "3A",
        quota: String = "GN",
        archived: Boolean = false,
        googleEventId: String? = null,
        lastFetchedAt: Instant? = null,
        updatedAt: Instant = NOW,
        deletedAt: Instant? = null,
    ): TrainTicket =
        TrainTicket(
            id,
            pnr,
            trainNumber,
            trainName,
            journeyDate,
            fromStation,
            toStation,
            travelClass,
            quota,
            archived,
            googleEventId,
            lastFetchedAt,
            updatedAt,
            deletedAt,
        )

    fun trainPassenger(
        id: String = EntityIds.newId(),
        ticketId: String = FIXED_ID,
        name: String = "Passenger 1",
        coach: String = "B2",
        seatBerth: String = "32 LB",
        bookingStatus: String = "CNF",
        currentStatus: String = "CNF",
        sortOrder: Int = 0,
        updatedAt: Instant = NOW,
        deletedAt: Instant? = null,
    ): TrainPassenger = TrainPassenger(id, ticketId, name, coach, seatBerth, bookingStatus, currentStatus, sortOrder, updatedAt, deletedAt)

    fun trainRouteStop(
        id: String = EntityIds.newId(),
        ticketId: String = FIXED_ID,
        stationName: String = "Fixture Junction",
        arrival: String = "10:05",
        departure: String = "10:10",
        platform: String = "1",
        day: Int = 1,
        sortOrder: Int = 0,
        updatedAt: Instant = NOW,
        deletedAt: Instant? = null,
    ): TrainRouteStop = TrainRouteStop(id, ticketId, stationName, arrival, departure, platform, day, sortOrder, updatedAt, deletedAt)

    fun trainCoach(
        id: String = EntityIds.newId(),
        ticketId: String = FIXED_ID,
        code: String = "B2",
        sortOrder: Int = 0,
        updatedAt: Instant = NOW,
        deletedAt: Instant? = null,
    ): TrainCoach = TrainCoach(id, ticketId, code, sortOrder, updatedAt, deletedAt)

    fun flightJourney(
        id: String = EntityIds.newId(),
        airlineIata: String = "6E",
        flightNumber: String = "2345",
        date: LocalDate? = TODAY,
        pnrBookingRef: String = "AB1CD2",
        seat: String = "14A",
        cabinClass: String = "Economy",
        depAirport: String = "BLR",
        arrAirport: String = "DEL",
        schedDep: Instant? = NOW,
        schedArr: Instant? = NOW.plusSeconds(3 * 60 * 60),
        estDep: Instant? = null,
        estArr: Instant? = null,
        status: FlightStatus = FlightStatus.SCHEDULED,
        depTerminal: String = "",
        depGate: String = "",
        arrTerminal: String = "",
        arrGate: String = "",
        baggageBelt: String = "",
        aircraftType: String = "",
        archived: Boolean = false,
        lastFetchedAt: Instant? = null,
        boardingPassPath: String? = null,
        checkInUrl: String? = null,
        googleEventId: String? = null,
        updatedAt: Instant = NOW,
        deletedAt: Instant? = null,
    ): FlightJourney =
        FlightJourney(
            id,
            airlineIata,
            flightNumber,
            date,
            pnrBookingRef,
            seat,
            cabinClass,
            depAirport,
            arrAirport,
            schedDep,
            schedArr,
            estDep,
            estArr,
            status,
            depTerminal,
            depGate,
            arrTerminal,
            arrGate,
            baggageBelt,
            aircraftType,
            archived,
            lastFetchedAt,
            boardingPassPath,
            checkInUrl,
            googleEventId,
            updatedAt,
            deletedAt,
        )

    fun attachment(
        id: String = EntityIds.newId(),
        ownerType: AttachmentOwnerType = AttachmentOwnerType.TRAIN,
        ownerId: String = FIXED_ID,
        localPath: String = "/data/fixture/ticket.pdf",
        driveFileId: String? = null,
        mimeType: String = "application/pdf",
        updatedAt: Instant = NOW,
        deletedAt: Instant? = null,
    ): Attachment = Attachment(id, ownerType, ownerId, localPath, driveFileId, mimeType, updatedAt, deletedAt)

    fun travelDocument(
        id: String = EntityIds.newId(),
        name: String = "Passport",
        type: TravelDocumentType = TravelDocumentType.PASSPORT,
        filePath: String = "/data/fixture/passport.jpg",
        mimeType: String = "image/jpeg",
        addedAt: Instant = NOW,
        expiryDate: LocalDate? = null,
        note: String = "",
        driveFileId: String? = null,
        updatedAt: Instant = NOW,
        deletedAt: Instant? = null,
    ): TravelDocument = TravelDocument(id, name, type, filePath, mimeType, addedAt, expiryDate, note, driveFileId, updatedAt, deletedAt)
}
