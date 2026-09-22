package com.itsluminous.cleartravel.core.data.backup

import com.itsluminous.cleartravel.core.model.Attachment
import com.itsluminous.cleartravel.core.model.AttachmentOwnerType
import com.itsluminous.cleartravel.core.model.Checklist
import com.itsluminous.cleartravel.core.model.ChecklistItem
import com.itsluminous.cleartravel.core.model.ChecklistPreset
import com.itsluminous.cleartravel.core.model.ChecklistPresetItem
import com.itsluminous.cleartravel.core.model.CommuteMode
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
 * Domain model ↔ backup DTO mappers (ADR-015). Timestamps pass through UNCHANGED in
 * both directions — the whole merge algorithm depends on `updatedAt` fidelity.
 * Unknown enum storage values fall back safely via each enum's `fromStorage`.
 */

private fun Long.toInstant(): Instant = Instant.ofEpochMilli(this)

private fun String.toDate(): LocalDate = LocalDate.parse(this)

// ---- Trip ----

fun Trip.toDto(): TripDto =
    TripDto(
        id = id,
        name = name,
        destination = destination,
        startDate = startDate?.toString(),
        endDate = endDate?.toString(),
        coverEmoji = coverEmoji,
        coverColor = coverColor,
        archived = archived,
        updatedAt = updatedAt.toEpochMilli(),
        deletedAt = deletedAt?.toEpochMilli(),
    )

fun TripDto.toModel(): Trip =
    Trip(
        id = id,
        name = name,
        destination = destination,
        startDate = startDate?.toDate(),
        endDate = endDate?.toDate(),
        coverEmoji = coverEmoji,
        coverColor = coverColor,
        archived = archived,
        updatedAt = updatedAt.toInstant(),
        deletedAt = deletedAt?.toInstant(),
    )

// ---- ItineraryItem ----

fun ItineraryItem.toDto(): ItineraryItemDto =
    ItineraryItemDto(
        id = id,
        tripId = tripId,
        dayIndex = dayIndex,
        date = date?.toString(),
        orderInDay = orderInDay,
        type = type.storageValue,
        name = name,
        latitude = latitude,
        longitude = longitude,
        plannedTime = plannedTime,
        note = note,
        category = category.storageValue,
        link = link,
        commuteMode = commuteMode.storageValue,
        fromName = fromName,
        toName = toName,
        linkedJourneyId = linkedJourneyId,
        linkedJourneyType = linkedJourneyType?.storageValue,
        googleEventId = googleEventId,
        updatedAt = updatedAt.toEpochMilli(),
        deletedAt = deletedAt?.toEpochMilli(),
    )

fun ItineraryItemDto.toModel(): ItineraryItem =
    ItineraryItem(
        id = id,
        tripId = tripId,
        dayIndex = dayIndex,
        date = date?.toDate(),
        orderInDay = orderInDay,
        type = ItineraryItemType.fromStorage(type),
        name = name,
        latitude = latitude,
        longitude = longitude,
        plannedTime = plannedTime,
        note = note,
        category = PlaceCategory.fromStorage(category),
        link = link,
        commuteMode = CommuteMode.fromStorage(commuteMode),
        fromName = fromName,
        toName = toName,
        linkedJourneyId = linkedJourneyId,
        linkedJourneyType = linkedJourneyType?.let(JourneyType::fromStorage),
        googleEventId = googleEventId,
        updatedAt = updatedAt.toInstant(),
        deletedAt = deletedAt?.toInstant(),
    )

// ---- Checklists ----

fun Checklist.toDto(): ChecklistDto =
    ChecklistDto(
        id = id,
        tripId = tripId,
        name = name,
        updatedAt = updatedAt.toEpochMilli(),
        deletedAt = deletedAt?.toEpochMilli(),
    )

fun ChecklistDto.toModel(): Checklist =
    Checklist(
        id = id,
        tripId = tripId,
        name = name,
        updatedAt = updatedAt.toInstant(),
        deletedAt = deletedAt?.toInstant(),
    )

fun ChecklistItem.toDto(): ChecklistItemDto =
    ChecklistItemDto(
        id = id,
        checklistId = checklistId,
        text = text,
        checked = checked,
        sortOrder = sortOrder,
        updatedAt = updatedAt.toEpochMilli(),
        deletedAt = deletedAt?.toEpochMilli(),
    )

fun ChecklistItemDto.toModel(): ChecklistItem =
    ChecklistItem(
        id = id,
        checklistId = checklistId,
        text = text,
        checked = checked,
        sortOrder = sortOrder,
        updatedAt = updatedAt.toInstant(),
        deletedAt = deletedAt?.toInstant(),
    )

fun ChecklistPreset.toDto(): ChecklistPresetDto =
    ChecklistPresetDto(
        id = id,
        name = name,
        builtIn = builtIn,
        updatedAt = updatedAt.toEpochMilli(),
        deletedAt = deletedAt?.toEpochMilli(),
    )

fun ChecklistPresetDto.toModel(): ChecklistPreset =
    ChecklistPreset(
        id = id,
        name = name,
        builtIn = builtIn,
        updatedAt = updatedAt.toInstant(),
        deletedAt = deletedAt?.toInstant(),
    )

fun ChecklistPresetItem.toDto(): ChecklistPresetItemDto =
    ChecklistPresetItemDto(
        id = id,
        presetId = presetId,
        text = text,
        sortOrder = sortOrder,
        updatedAt = updatedAt.toEpochMilli(),
        deletedAt = deletedAt?.toEpochMilli(),
    )

fun ChecklistPresetItemDto.toModel(): ChecklistPresetItem =
    ChecklistPresetItem(
        id = id,
        presetId = presetId,
        text = text,
        sortOrder = sortOrder,
        updatedAt = updatedAt.toInstant(),
        deletedAt = deletedAt?.toInstant(),
    )

// ---- Trains ----

fun TrainTicket.toDto(): TrainTicketDto =
    TrainTicketDto(
        id = id,
        pnr = pnr,
        trainNumber = trainNumber,
        trainName = trainName,
        journeyDate = journeyDate?.toString(),
        fromStation = fromStation,
        toStation = toStation,
        travelClass = travelClass,
        quota = quota,
        archived = archived,
        googleEventId = googleEventId,
        lastFetchedAt = lastFetchedAt?.toEpochMilli(),
        updatedAt = updatedAt.toEpochMilli(),
        deletedAt = deletedAt?.toEpochMilli(),
    )

fun TrainTicketDto.toModel(): TrainTicket =
    TrainTicket(
        id = id,
        pnr = pnr,
        trainNumber = trainNumber,
        trainName = trainName,
        journeyDate = journeyDate?.toDate(),
        fromStation = fromStation,
        toStation = toStation,
        travelClass = travelClass,
        quota = quota,
        archived = archived,
        googleEventId = googleEventId,
        lastFetchedAt = lastFetchedAt?.toInstant(),
        updatedAt = updatedAt.toInstant(),
        deletedAt = deletedAt?.toInstant(),
    )

fun TrainPassenger.toDto(): TrainPassengerDto =
    TrainPassengerDto(
        id = id,
        ticketId = ticketId,
        name = name,
        coach = coach,
        seatBerth = seatBerth,
        bookingStatus = bookingStatus,
        currentStatus = currentStatus,
        sortOrder = sortOrder,
        updatedAt = updatedAt.toEpochMilli(),
        deletedAt = deletedAt?.toEpochMilli(),
    )

fun TrainPassengerDto.toModel(): TrainPassenger =
    TrainPassenger(
        id = id,
        ticketId = ticketId,
        name = name,
        coach = coach,
        seatBerth = seatBerth,
        bookingStatus = bookingStatus,
        currentStatus = currentStatus,
        sortOrder = sortOrder,
        updatedAt = updatedAt.toInstant(),
        deletedAt = deletedAt?.toInstant(),
    )

fun TrainRouteStop.toDto(): TrainRouteStopDto =
    TrainRouteStopDto(
        id = id,
        ticketId = ticketId,
        stationName = stationName,
        arrival = arrival,
        departure = departure,
        platform = platform,
        day = day,
        sortOrder = sortOrder,
        updatedAt = updatedAt.toEpochMilli(),
        deletedAt = deletedAt?.toEpochMilli(),
    )

fun TrainRouteStopDto.toModel(): TrainRouteStop =
    TrainRouteStop(
        id = id,
        ticketId = ticketId,
        stationName = stationName,
        arrival = arrival,
        departure = departure,
        platform = platform,
        day = day,
        sortOrder = sortOrder,
        updatedAt = updatedAt.toInstant(),
        deletedAt = deletedAt?.toInstant(),
    )

fun TrainCoach.toDto(): TrainCoachDto =
    TrainCoachDto(
        id = id,
        ticketId = ticketId,
        code = code,
        sortOrder = sortOrder,
        updatedAt = updatedAt.toEpochMilli(),
        deletedAt = deletedAt?.toEpochMilli(),
    )

fun TrainCoachDto.toModel(): TrainCoach =
    TrainCoach(
        id = id,
        ticketId = ticketId,
        code = code,
        sortOrder = sortOrder,
        updatedAt = updatedAt.toInstant(),
        deletedAt = deletedAt?.toInstant(),
    )

// ---- Flights ----

fun FlightJourney.toDto(): FlightJourneyDto =
    FlightJourneyDto(
        id = id,
        airlineIata = airlineIata,
        flightNumber = flightNumber,
        date = date?.toString(),
        pnrBookingRef = pnrBookingRef,
        seat = seat,
        cabinClass = cabinClass,
        depAirport = depAirport,
        arrAirport = arrAirport,
        schedDep = schedDep?.toEpochMilli(),
        schedArr = schedArr?.toEpochMilli(),
        estDep = estDep?.toEpochMilli(),
        estArr = estArr?.toEpochMilli(),
        status = status.storageValue,
        depTerminal = depTerminal,
        depGate = depGate,
        arrTerminal = arrTerminal,
        arrGate = arrGate,
        baggageBelt = baggageBelt,
        aircraftType = aircraftType,
        archived = archived,
        lastFetchedAt = lastFetchedAt?.toEpochMilli(),
        boardingPassPath = boardingPassPath,
        checkInUrl = checkInUrl,
        googleEventId = googleEventId,
        updatedAt = updatedAt.toEpochMilli(),
        deletedAt = deletedAt?.toEpochMilli(),
    )

fun FlightJourneyDto.toModel(): FlightJourney =
    FlightJourney(
        id = id,
        airlineIata = airlineIata,
        flightNumber = flightNumber,
        date = date?.toDate(),
        pnrBookingRef = pnrBookingRef,
        seat = seat,
        cabinClass = cabinClass,
        depAirport = depAirport,
        arrAirport = arrAirport,
        schedDep = schedDep?.toInstant(),
        schedArr = schedArr?.toInstant(),
        estDep = estDep?.toInstant(),
        estArr = estArr?.toInstant(),
        status = FlightStatus.fromStorage(status),
        depTerminal = depTerminal,
        depGate = depGate,
        arrTerminal = arrTerminal,
        arrGate = arrGate,
        baggageBelt = baggageBelt,
        aircraftType = aircraftType,
        archived = archived,
        lastFetchedAt = lastFetchedAt?.toInstant(),
        boardingPassPath = boardingPassPath,
        checkInUrl = checkInUrl,
        googleEventId = googleEventId,
        updatedAt = updatedAt.toInstant(),
        deletedAt = deletedAt?.toInstant(),
    )

// ---- Attachments ----

fun Attachment.toDto(bundled: Boolean): AttachmentDto =
    AttachmentDto(
        id = id,
        ownerType = ownerType.storageValue,
        ownerId = ownerId,
        localPath = localPath,
        driveFileId = driveFileId,
        mimeType = mimeType,
        bundled = bundled,
        updatedAt = updatedAt.toEpochMilli(),
        deletedAt = deletedAt?.toEpochMilli(),
    )

fun AttachmentDto.toModel(): Attachment =
    Attachment(
        id = id,
        ownerType = AttachmentOwnerType.fromStorage(ownerType),
        ownerId = ownerId,
        localPath = localPath,
        driveFileId = driveFileId,
        mimeType = mimeType,
        updatedAt = updatedAt.toInstant(),
        deletedAt = deletedAt?.toInstant(),
    )

// ---- Travel documents (ADR-027) ----

fun TravelDocument.toDto(bundled: Boolean): TravelDocumentDto =
    TravelDocumentDto(
        id = id,
        name = name,
        type = type.storageValue,
        filePath = filePath,
        mimeType = mimeType,
        addedAt = addedAt.toEpochMilli(),
        expiryDate = expiryDate?.toString(),
        note = note,
        driveFileId = driveFileId,
        bundled = bundled,
        updatedAt = updatedAt.toEpochMilli(),
        deletedAt = deletedAt?.toEpochMilli(),
    )

fun TravelDocumentDto.toModel(): TravelDocument =
    TravelDocument(
        id = id,
        name = name,
        type = TravelDocumentType.fromStorage(type),
        filePath = filePath,
        mimeType = mimeType,
        addedAt = addedAt.toInstant(),
        expiryDate = expiryDate?.toDate(),
        note = note,
        driveFileId = driveFileId,
        updatedAt = updatedAt.toInstant(),
        deletedAt = deletedAt?.toInstant(),
    )
