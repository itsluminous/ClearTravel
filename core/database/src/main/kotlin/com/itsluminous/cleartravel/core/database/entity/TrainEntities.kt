package com.itsluminous.cleartravel.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.itsluminous.cleartravel.core.model.TrainPassenger
import com.itsluminous.cleartravel.core.model.TrainRouteStop
import com.itsluminous.cleartravel.core.model.TrainTicket
import java.time.Instant
import java.time.LocalDate

/** Room row for [TrainTicket] (ADR-004). */
@Entity(tableName = "train_tickets")
data class TrainTicketEntity(
    @PrimaryKey val id: String,
    val pnr: String,
    @ColumnInfo(name = "train_number") val trainNumber: String,
    @ColumnInfo(name = "train_name") val trainName: String,
    @ColumnInfo(name = "journey_date") val journeyDate: LocalDate?,
    @ColumnInfo(name = "from_station") val fromStation: String,
    @ColumnInfo(name = "to_station") val toStation: String,
    @ColumnInfo(name = "travel_class") val travelClass: String,
    val quota: String,
    val archived: Boolean,
    @ColumnInfo(name = "google_event_id") val googleEventId: String?,
    @ColumnInfo(name = "last_fetched_at") val lastFetchedAt: Instant?,
    @ColumnInfo(name = "updated_at") val updatedAt: Instant,
    @ColumnInfo(name = "deleted_at") val deletedAt: Instant?,
)

/** Room row for [TrainPassenger] (ADR-004). */
@Entity(
    tableName = "train_passengers",
    indices = [Index("ticket_id")],
)
data class TrainPassengerEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "ticket_id") val ticketId: String,
    val name: String,
    val coach: String,
    @ColumnInfo(name = "seat_berth") val seatBerth: String,
    @ColumnInfo(name = "booking_status") val bookingStatus: String,
    @ColumnInfo(name = "current_status") val currentStatus: String,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
    @ColumnInfo(name = "updated_at") val updatedAt: Instant,
    @ColumnInfo(name = "deleted_at") val deletedAt: Instant?,
)

/** Room row for [TrainRouteStop] (ADR-004). */
@Entity(
    tableName = "train_route_stops",
    indices = [Index("ticket_id")],
)
data class TrainRouteStopEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "ticket_id") val ticketId: String,
    @ColumnInfo(name = "station_name") val stationName: String,
    val arrival: String,
    val departure: String,
    val platform: String,
    val day: Int,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
    @ColumnInfo(name = "updated_at") val updatedAt: Instant,
    @ColumnInfo(name = "deleted_at") val deletedAt: Instant?,
)

fun TrainTicket.toEntity(): TrainTicketEntity =
    TrainTicketEntity(
        id = id,
        pnr = pnr,
        trainNumber = trainNumber,
        trainName = trainName,
        journeyDate = journeyDate,
        fromStation = fromStation,
        toStation = toStation,
        travelClass = travelClass,
        quota = quota,
        archived = archived,
        googleEventId = googleEventId,
        lastFetchedAt = lastFetchedAt,
        updatedAt = updatedAt,
        deletedAt = deletedAt,
    )

fun TrainTicketEntity.toModel(): TrainTicket =
    TrainTicket(
        id = id,
        pnr = pnr,
        trainNumber = trainNumber,
        trainName = trainName,
        journeyDate = journeyDate,
        fromStation = fromStation,
        toStation = toStation,
        travelClass = travelClass,
        quota = quota,
        archived = archived,
        googleEventId = googleEventId,
        lastFetchedAt = lastFetchedAt,
        updatedAt = updatedAt,
        deletedAt = deletedAt,
    )

fun TrainPassenger.toEntity(): TrainPassengerEntity =
    TrainPassengerEntity(
        id = id,
        ticketId = ticketId,
        name = name,
        coach = coach,
        seatBerth = seatBerth,
        bookingStatus = bookingStatus,
        currentStatus = currentStatus,
        sortOrder = sortOrder,
        updatedAt = updatedAt,
        deletedAt = deletedAt,
    )

fun TrainPassengerEntity.toModel(): TrainPassenger =
    TrainPassenger(
        id = id,
        ticketId = ticketId,
        name = name,
        coach = coach,
        seatBerth = seatBerth,
        bookingStatus = bookingStatus,
        currentStatus = currentStatus,
        sortOrder = sortOrder,
        updatedAt = updatedAt,
        deletedAt = deletedAt,
    )

fun TrainRouteStop.toEntity(): TrainRouteStopEntity =
    TrainRouteStopEntity(
        id = id,
        ticketId = ticketId,
        stationName = stationName,
        arrival = arrival,
        departure = departure,
        platform = platform,
        day = day,
        sortOrder = sortOrder,
        updatedAt = updatedAt,
        deletedAt = deletedAt,
    )

fun TrainRouteStopEntity.toModel(): TrainRouteStop =
    TrainRouteStop(
        id = id,
        ticketId = ticketId,
        stationName = stationName,
        arrival = arrival,
        departure = departure,
        platform = platform,
        day = day,
        sortOrder = sortOrder,
        updatedAt = updatedAt,
        deletedAt = deletedAt,
    )
