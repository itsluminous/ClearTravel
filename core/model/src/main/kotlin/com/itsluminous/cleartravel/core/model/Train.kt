package com.itsluminous.cleartravel.core.model

import java.time.Instant
import java.time.LocalDate

/**
 * A train ticket (one PNR). Per-passenger seat/status lives in [TrainPassenger];
 * the known route in [TrainRouteStop].
 */
data class TrainTicket(
    override val id: String = EntityIds.newId(),
    /** 10-digit Indian Railways PNR. */
    val pnr: String,
    val trainNumber: String = "",
    val trainName: String = "",
    val journeyDate: LocalDate? = null,
    val fromStation: String = "",
    val toStation: String = "",
    /** Travel class code, e.g. "SL", "3A", "2A", "CC". */
    val travelClass: String = "",
    /** Booking quota code, e.g. "GN", "TQ", "LD". */
    val quota: String = "",
    val archived: Boolean = false,
    /** Google Calendar event id when synced; null = never synced. */
    val googleEventId: String? = null,
    /** When the PNR status was last fetched from a provider; null = never. */
    val lastFetchedAt: Instant? = null,
    override val updatedAt: Instant = Instant.now(),
    override val deletedAt: Instant? = null,
) : SyncableEntity

/** One passenger on a [TrainTicket] with booking + current (chart) status. */
data class TrainPassenger(
    override val id: String = EntityIds.newId(),
    val ticketId: String,
    val name: String = "",
    val coach: String = "",
    /** Seat/berth, e.g. "32 LB". */
    val seatBerth: String = "",
    /** Status at booking time, e.g. "CNF", "RAC 12", "WL 45". */
    val bookingStatus: String = "",
    /** Latest known status after refresh, e.g. "CNF", "RAC 4", "WL 12". */
    val currentStatus: String = "",
    val sortOrder: Int = 0,
    override val updatedAt: Instant = Instant.now(),
    override val deletedAt: Instant? = null,
) : SyncableEntity

/** One stop on a [TrainTicket]'s route (station list with times and platform). */
data class TrainRouteStop(
    override val id: String = EntityIds.newId(),
    val ticketId: String,
    val stationName: String,
    /** Scheduled arrival "HH:mm"; empty for the origin. */
    val arrival: String = "",
    /** Scheduled departure "HH:mm"; empty for the destination. */
    val departure: String = "",
    /** Platform number when known. */
    val platform: String = "",
    /** 1-based running day of the train at this stop. */
    val day: Int = 1,
    val sortOrder: Int = 0,
    override val updatedAt: Instant = Instant.now(),
    override val deletedAt: Instant? = null,
) : SyncableEntity
