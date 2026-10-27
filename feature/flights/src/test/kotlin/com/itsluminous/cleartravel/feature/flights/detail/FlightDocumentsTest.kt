package com.itsluminous.cleartravel.feature.flights.detail

import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.model.AttachmentOwnerType
import com.itsluminous.cleartravel.core.model.FlightWithPassengers
import com.itsluminous.cleartravel.core.testing.Fixtures
import com.itsluminous.cleartravel.core.testing.MainDispatcherRule
import com.itsluminous.cleartravel.feature.flights.FakeAttachmentRepository
import com.itsluminous.cleartravel.feature.flights.FakeBookingConfirmationImporter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class FlightDocumentsBuilderTest {
    @Test
    fun `boarding pass leads the list and attachments follow`() {
        val flight = Fixtures.flightJourney(boardingPassPath = "/data/passes/f1.jpg")
        val attachment =
            Fixtures.attachment(
                ownerType = AttachmentOwnerType.FLIGHT,
                ownerId = flight.id,
                localPath = "/data/attachments/a1.pdf",
            )

        val documents = buildFlightDocuments(FlightWithPassengers(flight), listOf(attachment))

        assertThat(documents).hasSize(2)
        assertThat(documents[0].type).isEqualTo(FlightDocumentType.BOARDING_PASS)
        assertThat(documents[0].path).isEqualTo("/data/passes/f1.jpg")
        assertThat(documents[1].type).isEqualTo(FlightDocumentType.BOOKING_CONFIRMATION)
        assertThat(documents[1].path).isEqualTo("/data/attachments/a1.pdf")
        assertThat(documents[1].attachmentId).isEqualTo(attachment.id)
    }

    @Test
    fun `the drive-registered boarding-pass attachment row is not listed twice`() {
        // ADR-016: DriveUploadEngine registers the boarding pass as a FLIGHT
        // attachment row keyed by the SAME local path.
        val flight = Fixtures.flightJourney(boardingPassPath = "/data/passes/f1.jpg")
        val passRow =
            Fixtures.attachment(
                ownerType = AttachmentOwnerType.FLIGHT,
                ownerId = flight.id,
                localPath = "/data/passes/f1.jpg",
            )

        val documents = buildFlightDocuments(FlightWithPassengers(flight), listOf(passRow))

        assertThat(documents).hasSize(1)
        assertThat(documents.single().type).isEqualTo(FlightDocumentType.BOARDING_PASS)
    }

    @Test
    fun `one boarding-pass row per passenger, each naming its passenger, and the legacy mirror is not repeated`() {
        val flight = Fixtures.flightJourney(boardingPassPath = "/data/passes/p1.jpg")
        val p1 = Fixtures.flightPassenger(flightId = flight.id, name = "A", boardingPassPath = "/data/passes/p1.jpg", sortOrder = 0)
        val p2 = Fixtures.flightPassenger(flightId = flight.id, name = "B", boardingPassPath = "/data/passes/p2.pdf", sortOrder = 1)
        val noPass = Fixtures.flightPassenger(flightId = flight.id, name = "C", boardingPassPath = null, sortOrder = 2)
        val mirrorRow = Fixtures.attachment(ownerType = AttachmentOwnerType.FLIGHT, ownerId = flight.id, localPath = "/data/passes/p2.pdf")

        val documents = buildFlightDocuments(FlightWithPassengers(flight, listOf(p1, p2, noPass)), listOf(mirrorRow))

        assertThat(documents.map { it.type }).containsExactly(FlightDocumentType.BOARDING_PASS, FlightDocumentType.BOARDING_PASS)
        assertThat(documents.map { it.passenger }).containsExactly(p1, p2).inOrder()
        assertThat(documents.map { it.path }).containsExactly("/data/passes/p1.jpg", "/data/passes/p2.pdf").inOrder()
    }

    @Test
    fun `no boarding pass and no attachments yields an empty documents list`() {
        val flight = Fixtures.flightJourney(boardingPassPath = null)
        assertThat(buildFlightDocuments(FlightWithPassengers(flight), emptyList())).isEmpty()
    }

    @Test
    fun `attachment rows without a local copy yet are skipped`() {
        val flight = Fixtures.flightJourney(boardingPassPath = null)
        val driveOnly =
            Fixtures.attachment(
                ownerType = AttachmentOwnerType.FLIGHT,
                ownerId = flight.id,
                localPath = "",
                driveFileId = "drive-1",
            )

        assertThat(buildFlightDocuments(FlightWithPassengers(flight), listOf(driveOnly))).isEmpty()
    }
}

class FlightDocumentsViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `attach stores a FLIGHT attachment row and reports success`() =
        runTest {
            val importer = FakeBookingConfirmationImporter()
            val viewModel = FlightDocumentsViewModel(importer.attachmentRepository, importer)

            var result: Boolean? = null
            viewModel.attachBookingConfirmation("flight-1", "content://picked/booking") { result = it }

            assertThat(result).isTrue()
            assertThat(importer.attached).containsExactly("content://picked/booking" to "flight-1")
            val rows = viewModel.observeAttachments("flight-1").first()
            assertThat(rows).hasSize(1)
            assertThat(rows.single().ownerType).isEqualTo(AttachmentOwnerType.FLIGHT)
            assertThat(rows.single().driveFileId).isNull() // Drive queue picks it up (ADR-016)
        }

    @Test
    fun `a failed attach reports failure and persists nothing`() =
        runTest {
            val importer = FakeBookingConfirmationImporter(attachSucceeds = false)
            val viewModel = FlightDocumentsViewModel(importer.attachmentRepository, importer)

            var result: Boolean? = null
            viewModel.attachBookingConfirmation("flight-1", "content://picked/broken") { result = it }

            assertThat(result).isFalse()
            assertThat(viewModel.observeAttachments("flight-1").first()).isEmpty()
        }

    @Test
    fun `observeAttachments only surfaces rows owned by the flight`() =
        runTest {
            val repository = FakeAttachmentRepository()
            repository.seed(
                Fixtures.attachment(ownerType = AttachmentOwnerType.FLIGHT, ownerId = "flight-1"),
                Fixtures.attachment(ownerType = AttachmentOwnerType.FLIGHT, ownerId = "flight-2"),
                Fixtures.attachment(ownerType = AttachmentOwnerType.TRAIN, ownerId = "flight-1"),
            )
            val viewModel = FlightDocumentsViewModel(repository, FakeBookingConfirmationImporter())

            val rows = viewModel.observeAttachments("flight-1").first()

            assertThat(rows).hasSize(1)
            assertThat(rows.single().ownerId).isEqualTo("flight-1")
            assertThat(rows.single().ownerType).isEqualTo(AttachmentOwnerType.FLIGHT)
        }
}
