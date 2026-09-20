package com.itsluminous.cleartravel.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.itsluminous.cleartravel.core.model.CommuteMode
import com.itsluminous.cleartravel.core.model.ItineraryItem
import com.itsluminous.cleartravel.core.model.ItineraryItemType
import com.itsluminous.cleartravel.core.model.JourneyType
import com.itsluminous.cleartravel.core.model.PlaceCategory
import com.itsluminous.cleartravel.core.model.Trip
import java.time.Instant
import java.time.LocalDate

/** Room row for [Trip] (ADR-004). Columns follow ADR-002: uuid id + updated_at + deleted_at. */
@Entity(tableName = "trips")
data class TripEntity(
    @PrimaryKey val id: String,
    val name: String,
    val destination: String,
    @ColumnInfo(name = "start_date") val startDate: LocalDate?,
    @ColumnInfo(name = "end_date") val endDate: LocalDate?,
    @ColumnInfo(name = "cover_emoji") val coverEmoji: String,
    @ColumnInfo(name = "cover_color") val coverColor: String,
    val archived: Boolean,
    @ColumnInfo(name = "updated_at") val updatedAt: Instant,
    @ColumnInfo(name = "deleted_at") val deletedAt: Instant?,
)

/** Room row for [ItineraryItem] (ADR-004). */
@Entity(
    tableName = "itinerary_items",
    indices = [Index("trip_id")],
)
data class ItineraryItemEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "trip_id") val tripId: String,
    @ColumnInfo(name = "day_index") val dayIndex: Int,
    val date: LocalDate?,
    @ColumnInfo(name = "order_in_day") val orderInDay: Int,
    val type: ItineraryItemType,
    val name: String,
    val latitude: Double?,
    val longitude: Double?,
    @ColumnInfo(name = "planned_time") val plannedTime: String,
    val note: String,
    val category: PlaceCategory,
    val link: String,
    @ColumnInfo(name = "commute_mode") val commuteMode: CommuteMode,
    @ColumnInfo(name = "from_name") val fromName: String,
    @ColumnInfo(name = "to_name") val toName: String,
    @ColumnInfo(name = "linked_journey_id") val linkedJourneyId: String?,
    @ColumnInfo(name = "linked_journey_type") val linkedJourneyType: JourneyType?,
    @ColumnInfo(name = "google_event_id") val googleEventId: String?,
    @ColumnInfo(name = "updated_at") val updatedAt: Instant,
    @ColumnInfo(name = "deleted_at") val deletedAt: Instant?,
)

fun Trip.toEntity(): TripEntity =
    TripEntity(
        id = id,
        name = name,
        destination = destination,
        startDate = startDate,
        endDate = endDate,
        coverEmoji = coverEmoji,
        coverColor = coverColor,
        archived = archived,
        updatedAt = updatedAt,
        deletedAt = deletedAt,
    )

fun TripEntity.toModel(): Trip =
    Trip(
        id = id,
        name = name,
        destination = destination,
        startDate = startDate,
        endDate = endDate,
        coverEmoji = coverEmoji,
        coverColor = coverColor,
        archived = archived,
        updatedAt = updatedAt,
        deletedAt = deletedAt,
    )

fun ItineraryItem.toEntity(): ItineraryItemEntity =
    ItineraryItemEntity(
        id = id,
        tripId = tripId,
        dayIndex = dayIndex,
        date = date,
        orderInDay = orderInDay,
        type = type,
        name = name,
        latitude = latitude,
        longitude = longitude,
        plannedTime = plannedTime,
        note = note,
        category = category,
        link = link,
        commuteMode = commuteMode,
        fromName = fromName,
        toName = toName,
        linkedJourneyId = linkedJourneyId,
        linkedJourneyType = linkedJourneyType,
        googleEventId = googleEventId,
        updatedAt = updatedAt,
        deletedAt = deletedAt,
    )

fun ItineraryItemEntity.toModel(): ItineraryItem =
    ItineraryItem(
        id = id,
        tripId = tripId,
        dayIndex = dayIndex,
        date = date,
        orderInDay = orderInDay,
        type = type,
        name = name,
        latitude = latitude,
        longitude = longitude,
        plannedTime = plannedTime,
        note = note,
        category = category,
        link = link,
        commuteMode = commuteMode,
        fromName = fromName,
        toName = toName,
        linkedJourneyId = linkedJourneyId,
        linkedJourneyType = linkedJourneyType,
        googleEventId = googleEventId,
        updatedAt = updatedAt,
        deletedAt = deletedAt,
    )
