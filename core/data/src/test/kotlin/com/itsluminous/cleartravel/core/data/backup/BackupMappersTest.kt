package com.itsluminous.cleartravel.core.data.backup

import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.model.CommuteMode
import com.itsluminous.cleartravel.core.model.FlightStatus
import com.itsluminous.cleartravel.core.model.ItineraryItemType
import com.itsluminous.cleartravel.core.model.JourneyType
import com.itsluminous.cleartravel.core.model.TravelDocumentType
import com.itsluminous.cleartravel.core.testing.Fixtures
import org.junit.Test

/**
 * Model ↔ DTO round trips: every field — most importantly `updatedAt`/`deletedAt`,
 * which the merge algorithm depends on — must survive both directions unchanged.
 */
class BackupMappersTest {
    private val deletedAt = Fixtures.NOW.plusSeconds(120)

    @Test
    fun `trip round-trips including tombstone`() {
        val trip = Fixtures.trip(archived = true, deletedAt = deletedAt)
        assertThat(trip.toDto().toModel()).isEqualTo(trip)
    }

    @Test
    fun `itinerary item round-trips including commute fields`() {
        val item =
            Fixtures.itineraryItem(
                type = ItineraryItemType.COMMUTE,
                commuteMode = CommuteMode.TRAIN,
                fromName = "A",
                toName = "B",
                linkedJourneyId = Fixtures.FIXED_ID,
                linkedJourneyType = JourneyType.TRAIN,
                googleEventId = "evt-1",
                deletedAt = deletedAt,
            )
        assertThat(item.toDto().toModel()).isEqualTo(item)
    }

    @Test
    fun `checklist family round-trips`() {
        val checklist = Fixtures.checklist(tripId = Fixtures.FIXED_ID, deletedAt = deletedAt)
        val item = Fixtures.checklistItem(checked = true, sortOrder = 7, deletedAt = deletedAt)
        val preset = Fixtures.checklistPreset(builtIn = true, deletedAt = deletedAt)
        val presetItem = Fixtures.checklistPresetItem(sortOrder = 3, deletedAt = deletedAt)

        assertThat(checklist.toDto().toModel()).isEqualTo(checklist)
        assertThat(item.toDto().toModel()).isEqualTo(item)
        assertThat(preset.toDto().toModel()).isEqualTo(preset)
        assertThat(presetItem.toDto().toModel()).isEqualTo(presetItem)
    }

    @Test
    fun `train family round-trips`() {
        val ticket =
            Fixtures.trainTicket(
                googleEventId = "evt-2",
                lastFetchedAt = Fixtures.NOW.plusSeconds(5),
                archived = true,
                deletedAt = deletedAt,
            )
        val passenger = Fixtures.trainPassenger(deletedAt = deletedAt)
        val stop = Fixtures.trainRouteStop(deletedAt = deletedAt)
        val coach = Fixtures.trainCoach(code = "S1", sortOrder = 3, deletedAt = deletedAt)

        assertThat(ticket.toDto().toModel()).isEqualTo(ticket)
        assertThat(passenger.toDto().toModel()).isEqualTo(passenger)
        assertThat(stop.toDto().toModel()).isEqualTo(stop)
        assertThat(coach.toDto().toModel()).isEqualTo(coach)
    }

    @Test
    fun `flight round-trips including status enum and nullable instants`() {
        val flight =
            Fixtures.flightJourney(
                estDep = Fixtures.NOW.plusSeconds(600),
                estArr = Fixtures.NOW.plusSeconds(12000),
                status = FlightStatus.DELAYED,
                boardingPassPath = "/data/x/bp.pdf",
                checkInUrl = "https://example.com/checkin",
                googleEventId = "evt-3",
                lastFetchedAt = Fixtures.NOW,
                deletedAt = deletedAt,
            )
        assertThat(flight.toDto().toModel()).isEqualTo(flight)
    }

    @Test
    fun `attachment round-trips - bundled flag is transport-only`() {
        val attachment = Fixtures.attachment(driveFileId = "drive-123", deletedAt = deletedAt)
        assertThat(attachment.toDto(bundled = false).toModel()).isEqualTo(attachment)
        assertThat(attachment.toDto(bundled = true).toModel()).isEqualTo(attachment)
    }

    @Test
    fun `travel document round-trips including expiry and unknown type fallback`() {
        val document =
            Fixtures.travelDocument(
                type = TravelDocumentType.VISA,
                expiryDate = Fixtures.TODAY.plusYears(2),
                note = "Schengen, multi-entry",
                deletedAt = deletedAt,
            )
        assertThat(document.toDto(bundled = true).toModel()).isEqualTo(document)
        assertThat(document.toDto(bundled = false).toModel()).isEqualTo(document)

        val fromTheFuture = document.toDto(bundled = false).copy(type = "hologram")
        assertThat(fromTheFuture.toModel().type).isEqualTo(TravelDocumentType.OTHER)
    }

    @Test
    fun `unknown enum storage values fall back safely on import`() {
        val dto =
            Fixtures.itineraryItem().toDto().copy(
                type = "from-the-future",
                category = "from-the-future",
                commuteMode = "from-the-future",
            )
        val model = dto.toModel()

        assertThat(model.type).isEqualTo(ItineraryItemType.PLACE)
        assertThat(model.commuteMode).isEqualTo(CommuteMode.OTHER)

        val flightDto = Fixtures.flightJourney().toDto().copy(status = "from-the-future")
        assertThat(flightDto.toModel().status).isEqualTo(FlightStatus.UNKNOWN)
    }
}
