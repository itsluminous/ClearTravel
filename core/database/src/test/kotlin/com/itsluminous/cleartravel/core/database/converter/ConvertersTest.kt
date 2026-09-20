package com.itsluminous.cleartravel.core.database.converter

import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.model.AttachmentOwnerType
import com.itsluminous.cleartravel.core.model.CommuteMode
import com.itsluminous.cleartravel.core.model.FlightStatus
import com.itsluminous.cleartravel.core.model.ItineraryItemType
import com.itsluminous.cleartravel.core.model.JourneyType
import com.itsluminous.cleartravel.core.model.PlaceCategory
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class ConvertersTest {
    @Test
    fun `instant round-trips through epoch millis`() {
        val instant = Instant.parse("2026-09-20T09:00:00Z")
        assertThat(Converters.epochMillisToInstant(Converters.instantToEpochMillis(instant))).isEqualTo(instant)
        assertThat(Converters.instantToEpochMillis(null)).isNull()
        assertThat(Converters.epochMillisToInstant(null)).isNull()
    }

    @Test
    fun `local date round-trips through iso string`() {
        val date = LocalDate.parse("2026-09-20")
        assertThat(Converters.stringToLocalDate(Converters.localDateToString(date))).isEqualTo(date)
        assertThat(Converters.localDateToString(null)).isNull()
    }

    @Test
    fun `every enum round-trips through its storage value`() {
        ItineraryItemType.entries.forEach {
            assertThat(Converters.stringToItineraryItemType(Converters.itineraryItemTypeToString(it))).isEqualTo(it)
        }
        PlaceCategory.entries.forEach {
            assertThat(Converters.stringToPlaceCategory(Converters.placeCategoryToString(it))).isEqualTo(it)
        }
        CommuteMode.entries.forEach {
            assertThat(Converters.stringToCommuteMode(Converters.commuteModeToString(it))).isEqualTo(it)
        }
        JourneyType.entries.forEach {
            assertThat(Converters.stringToJourneyType(Converters.journeyTypeToString(it))).isEqualTo(it)
        }
        FlightStatus.entries.forEach {
            assertThat(Converters.stringToFlightStatus(Converters.flightStatusToString(it))).isEqualTo(it)
        }
        AttachmentOwnerType.entries.forEach {
            assertThat(Converters.stringToAttachmentOwnerType(Converters.attachmentOwnerTypeToString(it))).isEqualTo(it)
        }
    }

    @Test
    fun `unknown storage values fall back instead of crashing`() {
        assertThat(Converters.stringToFlightStatus("something-new")).isEqualTo(FlightStatus.UNKNOWN)
        assertThat(Converters.stringToPlaceCategory("something-new")).isEqualTo(PlaceCategory.OTHER)
        assertThat(Converters.stringToCommuteMode("something-new")).isEqualTo(CommuteMode.OTHER)
    }
}
