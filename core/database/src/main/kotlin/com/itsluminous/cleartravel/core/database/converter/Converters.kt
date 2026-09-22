package com.itsluminous.cleartravel.core.database.converter

import androidx.room.TypeConverter
import com.itsluminous.cleartravel.core.model.AttachmentOwnerType
import com.itsluminous.cleartravel.core.model.CommuteMode
import com.itsluminous.cleartravel.core.model.FlightStatus
import com.itsluminous.cleartravel.core.model.ItineraryItemType
import com.itsluminous.cleartravel.core.model.JourneyType
import com.itsluminous.cleartravel.core.model.PlaceCategory
import com.itsluminous.cleartravel.core.model.TravelDocumentType
import java.time.Instant
import java.time.LocalDate

/**
 * Room type converters: [Instant] ↔ epoch millis, [LocalDate] ↔ ISO-8601 string,
 * enums ↔ their stable `storageValue` (never `name()`, so renames stay safe).
 */
object Converters {
    @TypeConverter
    fun instantToEpochMillis(value: Instant?): Long? = value?.toEpochMilli()

    @TypeConverter
    fun epochMillisToInstant(value: Long?): Instant? = value?.let(Instant::ofEpochMilli)

    @TypeConverter
    fun localDateToString(value: LocalDate?): String? = value?.toString()

    @TypeConverter
    fun stringToLocalDate(value: String?): LocalDate? = value?.let(LocalDate::parse)

    @TypeConverter
    fun itineraryItemTypeToString(value: ItineraryItemType): String = value.storageValue

    @TypeConverter
    fun stringToItineraryItemType(value: String): ItineraryItemType = ItineraryItemType.fromStorage(value)

    @TypeConverter
    fun placeCategoryToString(value: PlaceCategory): String = value.storageValue

    @TypeConverter
    fun stringToPlaceCategory(value: String): PlaceCategory = PlaceCategory.fromStorage(value)

    @TypeConverter
    fun commuteModeToString(value: CommuteMode): String = value.storageValue

    @TypeConverter
    fun stringToCommuteMode(value: String): CommuteMode = CommuteMode.fromStorage(value)

    @TypeConverter
    fun journeyTypeToString(value: JourneyType?): String? = value?.storageValue

    @TypeConverter
    fun stringToJourneyType(value: String?): JourneyType? = value?.let(JourneyType::fromStorage)

    @TypeConverter
    fun flightStatusToString(value: FlightStatus): String = value.storageValue

    @TypeConverter
    fun stringToFlightStatus(value: String): FlightStatus = FlightStatus.fromStorage(value)

    @TypeConverter
    fun attachmentOwnerTypeToString(value: AttachmentOwnerType): String = value.storageValue

    @TypeConverter
    fun stringToAttachmentOwnerType(value: String): AttachmentOwnerType = AttachmentOwnerType.fromStorage(value)

    @TypeConverter
    fun travelDocumentTypeToString(value: TravelDocumentType): String = value.storageValue

    @TypeConverter
    fun stringToTravelDocumentType(value: String): TravelDocumentType = TravelDocumentType.fromStorage(value)
}
