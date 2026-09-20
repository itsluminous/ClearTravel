package com.itsluminous.cleartravel.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.itsluminous.cleartravel.core.model.Attachment
import com.itsluminous.cleartravel.core.model.AttachmentOwnerType
import com.itsluminous.cleartravel.core.model.FlightJourney
import com.itsluminous.cleartravel.core.model.FlightStatus
import java.time.Instant
import java.time.LocalDate

/** Room row for [FlightJourney] (ADR-004). */
@Entity(tableName = "flight_journeys")
data class FlightJourneyEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "airline_iata") val airlineIata: String,
    @ColumnInfo(name = "flight_number") val flightNumber: String,
    val date: LocalDate?,
    @ColumnInfo(name = "pnr_booking_ref") val pnrBookingRef: String,
    val seat: String,
    @ColumnInfo(name = "cabin_class") val cabinClass: String,
    @ColumnInfo(name = "dep_airport") val depAirport: String,
    @ColumnInfo(name = "arr_airport") val arrAirport: String,
    @ColumnInfo(name = "sched_dep") val schedDep: Instant?,
    @ColumnInfo(name = "sched_arr") val schedArr: Instant?,
    @ColumnInfo(name = "est_dep") val estDep: Instant?,
    @ColumnInfo(name = "est_arr") val estArr: Instant?,
    val status: FlightStatus,
    @ColumnInfo(name = "dep_terminal") val depTerminal: String,
    @ColumnInfo(name = "dep_gate") val depGate: String,
    @ColumnInfo(name = "arr_terminal") val arrTerminal: String,
    @ColumnInfo(name = "arr_gate") val arrGate: String,
    @ColumnInfo(name = "baggage_belt") val baggageBelt: String,
    @ColumnInfo(name = "aircraft_type") val aircraftType: String,
    val archived: Boolean,
    @ColumnInfo(name = "last_fetched_at") val lastFetchedAt: Instant?,
    @ColumnInfo(name = "boarding_pass_path") val boardingPassPath: String?,
    @ColumnInfo(name = "check_in_url") val checkInUrl: String?,
    @ColumnInfo(name = "google_event_id") val googleEventId: String?,
    @ColumnInfo(name = "updated_at") val updatedAt: Instant,
    @ColumnInfo(name = "deleted_at") val deletedAt: Instant?,
)

/** Room row for [Attachment] (ADR-004). Polymorphic owner: (`owner_type`, `owner_id`). */
@Entity(
    tableName = "attachments",
    indices = [Index("owner_type", "owner_id")],
)
data class AttachmentEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "owner_type") val ownerType: AttachmentOwnerType,
    @ColumnInfo(name = "owner_id") val ownerId: String,
    @ColumnInfo(name = "local_path") val localPath: String,
    @ColumnInfo(name = "drive_file_id") val driveFileId: String?,
    @ColumnInfo(name = "mime_type") val mimeType: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Instant,
    @ColumnInfo(name = "deleted_at") val deletedAt: Instant?,
)

fun FlightJourney.toEntity(): FlightJourneyEntity =
    FlightJourneyEntity(
        id = id,
        airlineIata = airlineIata,
        flightNumber = flightNumber,
        date = date,
        pnrBookingRef = pnrBookingRef,
        seat = seat,
        cabinClass = cabinClass,
        depAirport = depAirport,
        arrAirport = arrAirport,
        schedDep = schedDep,
        schedArr = schedArr,
        estDep = estDep,
        estArr = estArr,
        status = status,
        depTerminal = depTerminal,
        depGate = depGate,
        arrTerminal = arrTerminal,
        arrGate = arrGate,
        baggageBelt = baggageBelt,
        aircraftType = aircraftType,
        archived = archived,
        lastFetchedAt = lastFetchedAt,
        boardingPassPath = boardingPassPath,
        checkInUrl = checkInUrl,
        googleEventId = googleEventId,
        updatedAt = updatedAt,
        deletedAt = deletedAt,
    )

fun FlightJourneyEntity.toModel(): FlightJourney =
    FlightJourney(
        id = id,
        airlineIata = airlineIata,
        flightNumber = flightNumber,
        date = date,
        pnrBookingRef = pnrBookingRef,
        seat = seat,
        cabinClass = cabinClass,
        depAirport = depAirport,
        arrAirport = arrAirport,
        schedDep = schedDep,
        schedArr = schedArr,
        estDep = estDep,
        estArr = estArr,
        status = status,
        depTerminal = depTerminal,
        depGate = depGate,
        arrTerminal = arrTerminal,
        arrGate = arrGate,
        baggageBelt = baggageBelt,
        aircraftType = aircraftType,
        archived = archived,
        lastFetchedAt = lastFetchedAt,
        boardingPassPath = boardingPassPath,
        checkInUrl = checkInUrl,
        googleEventId = googleEventId,
        updatedAt = updatedAt,
        deletedAt = deletedAt,
    )

fun Attachment.toEntity(): AttachmentEntity =
    AttachmentEntity(
        id = id,
        ownerType = ownerType,
        ownerId = ownerId,
        localPath = localPath,
        driveFileId = driveFileId,
        mimeType = mimeType,
        updatedAt = updatedAt,
        deletedAt = deletedAt,
    )

fun AttachmentEntity.toModel(): Attachment =
    Attachment(
        id = id,
        ownerType = ownerType,
        ownerId = ownerId,
        localPath = localPath,
        driveFileId = driveFileId,
        mimeType = mimeType,
        updatedAt = updatedAt,
        deletedAt = deletedAt,
    )
