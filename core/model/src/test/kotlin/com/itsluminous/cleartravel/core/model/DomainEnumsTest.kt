package com.itsluminous.cleartravel.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DomainEnumsTest {
    @Test
    fun `every enum round-trips its storage value`() {
        ItineraryItemType.entries.forEach { assertThat(ItineraryItemType.fromStorage(it.storageValue)).isEqualTo(it) }
        PlaceCategory.entries.forEach { assertThat(PlaceCategory.fromStorage(it.storageValue)).isEqualTo(it) }
        CommuteMode.entries.forEach { assertThat(CommuteMode.fromStorage(it.storageValue)).isEqualTo(it) }
        JourneyType.entries.forEach { assertThat(JourneyType.fromStorage(it.storageValue)).isEqualTo(it) }
        FlightStatus.entries.forEach { assertThat(FlightStatus.fromStorage(it.storageValue)).isEqualTo(it) }
        AttachmentOwnerType.entries.forEach { assertThat(AttachmentOwnerType.fromStorage(it.storageValue)).isEqualTo(it) }
    }

    @Test
    fun `unknown or null storage values fall back safely`() {
        assertThat(ItineraryItemType.fromStorage(null)).isEqualTo(ItineraryItemType.PLACE)
        assertThat(PlaceCategory.fromStorage("nope")).isEqualTo(PlaceCategory.OTHER)
        assertThat(CommuteMode.fromStorage("nope")).isEqualTo(CommuteMode.OTHER)
        assertThat(FlightStatus.fromStorage("nope")).isEqualTo(FlightStatus.UNKNOWN)
    }

    @Test
    fun `storage values are unique per enum`() {
        assertThat(PlaceCategory.entries.map { it.storageValue }.toSet()).hasSize(PlaceCategory.entries.size)
        assertThat(CommuteMode.entries.map { it.storageValue }.toSet()).hasSize(CommuteMode.entries.size)
        assertThat(FlightStatus.entries.map { it.storageValue }.toSet()).hasSize(FlightStatus.entries.size)
    }

    @Test
    fun `syncable entity defaults are merge-safe`() {
        val trip = Trip(name = "T")
        assertThat(EntityIds.isValid(trip.id)).isTrue()
        assertThat(trip.deletedAt).isNull()

        val flight = FlightJourney(airlineIata = "6E", flightNumber = "1")
        assertThat(EntityIds.isValid(flight.id)).isTrue()
        assertThat(flight.status).isEqualTo(FlightStatus.SCHEDULED)
    }
}
