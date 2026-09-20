package com.itsluminous.cleartravel.feature.flights.form

import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.model.AttachmentOwnerType
import com.itsluminous.cleartravel.core.model.FlightStatus
import com.itsluminous.cleartravel.core.ocr.ExtractionConfidence
import com.itsluminous.cleartravel.core.ocr.model.BoardingPassExtraction
import com.itsluminous.cleartravel.core.ocr.model.BoardingPassSource
import com.itsluminous.cleartravel.core.ocr.model.BookingConfirmationExtraction
import com.itsluminous.cleartravel.core.ocr.model.BookingConfirmationSource
import com.itsluminous.cleartravel.core.ocr.model.ExtractedField
import com.itsluminous.cleartravel.core.testing.Fixtures
import com.itsluminous.cleartravel.core.testing.MainDispatcherRule
import com.itsluminous.cleartravel.feature.flights.FakeBoardingPassImporter
import com.itsluminous.cleartravel.feature.flights.FakeBookingConfirmationImporter
import com.itsluminous.cleartravel.feature.flights.FakeCheckInRuleSource
import com.itsluminous.cleartravel.feature.flights.FakeFlightRepository
import com.itsluminous.cleartravel.feature.flights.checkin.AirlineCheckInInfo
import com.itsluminous.cleartravel.feature.flights.checkin.CheckInRules
import com.itsluminous.cleartravel.feature.flights.checkin.CheckInWindowSpec
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class FlightFormViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var repository: FakeFlightRepository
    private lateinit var importer: FakeBoardingPassImporter
    private lateinit var bookingImporter: FakeBookingConfirmationImporter
    private lateinit var viewModel: FlightFormViewModel

    private val checkInRules =
        CheckInRules(
            version = 1,
            defaultWindow = CheckInWindowSpec(48, 1),
            airlines =
                mapOf(
                    "6E" to AirlineCheckInInfo("IndiGo", 48, 1, "https://www.goindigo.in/web-check-in.html"),
                ),
        )

    @Before
    fun setUp() {
        repository = FakeFlightRepository()
        importer = FakeBoardingPassImporter()
        bookingImporter = FakeBookingConfirmationImporter()
        viewModel =
            FlightFormViewModel(
                repository,
                importer,
                bookingImporter,
                FakeCheckInRuleSource(checkInRules),
            )
    }

    @Test
    fun `save rejects an invalid form and surfaces errors`() =
        runTest {
            viewModel.startBlank()
            viewModel.update { it.copy(airlineIata = "6E") } // still missing number + date

            var saved: String? = null
            viewModel.save { saved = it }

            assertThat(saved).isNull()
            assertThat(viewModel.formState.value.errors).isNotEmpty()
            assertThat(repository.savedIds).isEmpty()
        }

    @Test
    fun `save persists a valid manual entry with the airline's check-in url`() =
        runTest {
            viewModel.startBlank()
            viewModel.update {
                it.copy(airlineIata = "6E", flightNumber = "2345", dateText = "2026-09-25", seat = "14A")
            }

            var saved: String? = null
            viewModel.save { saved = it }

            assertThat(saved).isNotNull()
            val journey = repository.getFlight(saved!!)!!
            assertThat(journey.airlineIata).isEqualTo("6E")
            assertThat(journey.seat).isEqualTo("14A")
            assertThat(journey.checkInUrl).isEqualTo("https://www.goindigo.in/web-check-in.html")
        }

    @Test
    fun `boarding pass import prefills the form with source and confidence`() =
        runTest {
            importer.extraction =
                BoardingPassExtraction(
                    pnr = ExtractedField.of("AB1CD2", ExtractionConfidence.HIGH),
                    carrier = ExtractedField.of("AI", ExtractionConfidence.HIGH),
                    flightNumber = ExtractedField.of("101", ExtractionConfidence.HIGH),
                    fromAirport = ExtractedField.of("DEL", ExtractionConfidence.HIGH),
                    toAirport = ExtractedField.of("FCO", ExtractionConfidence.HIGH),
                    flightDate = ExtractedField.of("2026-09-20", ExtractionConfidence.HIGH),
                    source = BoardingPassSource.BARCODE,
                )

            viewModel.startFromBoardingPass("content://picked/pass")

            val state = viewModel.formState.value
            assertThat(importer.prefilled).containsExactly("content://picked/pass")
            assertThat(state.airlineIata).isEqualTo("AI")
            assertThat(state.prefillSource).isEqualTo(BoardingPassSource.BARCODE)
            assertThat(state.pendingPassUri).isEqualTo("content://picked/pass")
        }

    @Test
    fun `unrecognized boarding pass degrades to a blank form with the file attached`() =
        runTest {
            importer.extraction = BoardingPassExtraction.EMPTY

            viewModel.startFromBoardingPass("content://picked/garbage")

            val state = viewModel.formState.value
            assertThat(state.airlineIata).isEmpty()
            assertThat(state.prefillSource).isEqualTo(BoardingPassSource.NONE)
            assertThat(state.pendingPassUri).isEqualTo("content://picked/garbage")
        }

    @Test
    fun `saving an imported pass stores the file and records its path`() =
        runTest {
            importer.extraction =
                BoardingPassExtraction(
                    carrier = ExtractedField.of("AI", ExtractionConfidence.HIGH),
                    flightNumber = ExtractedField.of("101", ExtractionConfidence.HIGH),
                    flightDate = ExtractedField.of("2026-09-20", ExtractionConfidence.HIGH),
                    source = BoardingPassSource.BARCODE,
                )
            importer.storedPath = "/data/passes/stored.pdf"
            viewModel.startFromBoardingPass("content://picked/pass")

            var saved: String? = null
            viewModel.save { saved = it }

            assertThat(importer.stored).containsExactly("content://picked/pass" to saved)
            assertThat(repository.getFlight(saved!!)!!.boardingPassPath).isEqualTo("/data/passes/stored.pdf")
        }

    @Test
    fun `edit merges user fields and preserves provider-fetched data`() =
        runTest {
            val existing =
                Fixtures.flightJourney(
                    status = FlightStatus.DELAYED,
                    depGate = "24",
                    baggageBelt = "7",
                    boardingPassPath = "/data/passes/old.jpg",
                )
            repository.seed(existing)
            viewModel.startEdit(existing.id)
            viewModel.update { it.copy(seat = "1A") }

            var saved: String? = null
            viewModel.save { saved = it }

            val updated = repository.getFlight(saved!!)!!
            assertThat(updated.id).isEqualTo(existing.id)
            assertThat(updated.seat).isEqualTo("1A")
            // Provider-owned fields survive the edit.
            assertThat(updated.status).isEqualTo(FlightStatus.DELAYED)
            assertThat(updated.depGate).isEqualTo("24")
            assertThat(updated.baggageBelt).isEqualTo("7")
            assertThat(updated.boardingPassPath).isEqualTo("/data/passes/old.jpg")
        }

    @Test
    fun `booking confirmation import prefills the form with source, confidence and return-leg hint`() =
        runTest {
            bookingImporter.extraction =
                BookingConfirmationExtraction(
                    pnr = ExtractedField.of("HJK92L", ExtractionConfidence.HIGH),
                    carrier = ExtractedField.of("AI", ExtractionConfidence.HIGH),
                    flightNumber = ExtractedField.of("503", ExtractionConfidence.MEDIUM),
                    fromAirport = ExtractedField.of("COK", ExtractionConfidence.MEDIUM),
                    toAirport = ExtractedField.of("DEL", ExtractionConfidence.MEDIUM),
                    flightDate = ExtractedField.of("2026-09-26", ExtractionConfidence.HIGH),
                    cabinClass = ExtractedField.of("BUSINESS", ExtractionConfidence.HIGH),
                    additionalFlights = 1,
                    source = BookingConfirmationSource.OCR_TEXT,
                )

            viewModel.startFromBookingConfirmation("content://picked/booking")

            val state = viewModel.formState.value
            assertThat(bookingImporter.prefilled).containsExactly("content://picked/booking")
            assertThat(state.airlineIata).isEqualTo("AI")
            assertThat(state.flightNumber).isEqualTo("503")
            assertThat(state.cabinClass).isEqualTo("BUSINESS")
            assertThat(state.bookingSource).isEqualTo(BookingConfirmationSource.OCR_TEXT)
            assertThat(state.pendingBookingUri).isEqualTo("content://picked/booking")
            assertThat(state.confidences[FlightField.PNR]).isEqualTo(ExtractionConfidence.HIGH)
            assertThat(state.confidences[FlightField.CABIN]).isEqualTo(ExtractionConfidence.HIGH)
            assertThat(state.confidences[FlightField.FLIGHT_NUMBER]).isEqualTo(ExtractionConfidence.MEDIUM)
            assertThat(state.returnLegHint).isTrue()
        }

    @Test
    fun `unrecognized booking confirmation degrades to a blank form with the file kept`() =
        runTest {
            bookingImporter.extraction = BookingConfirmationExtraction.EMPTY

            viewModel.startFromBookingConfirmation("content://picked/booking-garbage")

            val state = viewModel.formState.value
            assertThat(state.airlineIata).isEmpty()
            assertThat(state.bookingSource).isEqualTo(BookingConfirmationSource.NONE)
            assertThat(state.returnLegHint).isFalse()
            assertThat(state.pendingBookingUri).isEqualTo("content://picked/booking-garbage")
        }

    @Test
    fun `saving a booking import persists a FLIGHT attachment row and leaves the boarding pass untouched`() =
        runTest {
            bookingImporter.extraction =
                BookingConfirmationExtraction(
                    carrier = ExtractedField.of("AI", ExtractionConfidence.HIGH),
                    flightNumber = ExtractedField.of("503", ExtractionConfidence.HIGH),
                    flightDate = ExtractedField.of("2026-09-26", ExtractionConfidence.HIGH),
                    source = BookingConfirmationSource.OCR_TEXT,
                )
            viewModel.startFromBookingConfirmation("content://picked/booking")

            var saved: String? = null
            viewModel.save { saved = it }

            assertThat(bookingImporter.attached).containsExactly("content://picked/booking" to saved)
            val rows =
                bookingImporter.attachmentRepository
                    .observeForOwner(AttachmentOwnerType.FLIGHT, saved!!)
                    .first()
            assertThat(rows).hasSize(1)
            assertThat(rows.single().driveFileId).isNull() // pending Drive upload (ADR-016)
            // Boarding-pass column stays frozen: booking confirmations never touch it.
            assertThat(repository.getFlight(saved!!)!!.boardingPassPath).isNull()
            assertThat(importer.stored).isEmpty()
        }

    @Test
    fun `a failed booking attach still saves the flight`() =
        runTest {
            bookingImporter.extraction =
                BookingConfirmationExtraction(
                    carrier = ExtractedField.of("6E", ExtractionConfidence.HIGH),
                    flightNumber = ExtractedField.of("6114", ExtractionConfidence.HIGH),
                    flightDate = ExtractedField.of("2026-11-05", ExtractionConfidence.HIGH),
                    source = BookingConfirmationSource.OCR_TEXT,
                )
            bookingImporter.attachSucceeds = false
            viewModel.startFromBookingConfirmation("content://picked/booking")

            var saved: String? = null
            viewModel.save { saved = it }

            assertThat(saved).isNotNull()
            assertThat(repository.getFlight(saved!!)).isNotNull()
            assertThat(
                bookingImporter.attachmentRepository
                    .observeForOwner(AttachmentOwnerType.FLIGHT, saved!!)
                    .first(),
            ).isEmpty()
        }
}
