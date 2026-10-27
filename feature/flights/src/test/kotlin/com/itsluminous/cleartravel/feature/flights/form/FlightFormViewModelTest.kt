package com.itsluminous.cleartravel.feature.flights.form

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.model.AttachmentOwnerType
import com.itsluminous.cleartravel.core.model.FlightStatus
import com.itsluminous.cleartravel.core.ocr.ExtractionConfidence
import com.itsluminous.cleartravel.core.ocr.model.BoardingPassExtraction
import com.itsluminous.cleartravel.core.ocr.model.BoardingPassSource
import com.itsluminous.cleartravel.core.ocr.model.BookingConfirmationExtraction
import com.itsluminous.cleartravel.core.ocr.model.BookingConfirmationSource
import com.itsluminous.cleartravel.core.ocr.model.ExtractedField
import com.itsluminous.cleartravel.core.ocr.model.ExtractedFlight
import com.itsluminous.cleartravel.core.testing.Fixtures
import com.itsluminous.cleartravel.core.testing.MainDispatcherRule
import com.itsluminous.cleartravel.feature.flights.FakeBoardingPassImporter
import com.itsluminous.cleartravel.feature.flights.FakeBookingConfirmationImporter
import com.itsluminous.cleartravel.feature.flights.FakeCheckInRuleSource
import com.itsluminous.cleartravel.feature.flights.FakeFlightRepository
import com.itsluminous.cleartravel.feature.flights.checkin.AirlineCheckInInfo
import com.itsluminous.cleartravel.feature.flights.checkin.CheckInRules
import com.itsluminous.cleartravel.feature.flights.checkin.CheckInWindowSpec
import com.itsluminous.cleartravel.feature.flights.text.AirlineSmsRulesFixture
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

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
                AirlineSmsRulesFixture.parser(),
            )
    }

    @Test
    fun `startFromText prefills the form from an airline SMS with confidence markers`() =
        runTest {
            viewModel.startFromText(AirlineSmsRulesFixture.read("akasa-1.txt"))

            val state = viewModel.formState.value
            assertThat(state.airlineIata).isEqualTo("QP")
            assertThat(state.flightNumber).isEqualTo("1421")
            assertThat(state.pnr).isEqualTo("X4F18V")
            assertThat(state.depAirport).isEqualTo("BLR")
            assertThat(state.arrAirport).isEqualTo("VNS")
            assertThat(state.dateText).isEqualTo("2026-05-29")
            assertThat(state.depTerminal).isEqualTo("1")
            assertThat(state.fromText).isTrue()
            assertThat(state.confidences[FlightField.AIRLINE]).isEqualTo(ExtractionConfidence.HIGH)
            assertThat(state.confidences[FlightField.PNR]).isEqualTo(ExtractionConfidence.HIGH)
            // Nothing parsed for seat/cabin: those fields stay empty with no marker.
            assertThat(state.seat).isEmpty()
            assertThat(state.confidences).doesNotContainKey(FlightField.SEAT)
            assertThat(FlightFormState.validate(state)).isEmpty()
        }

    @Test
    fun `startFromText with no flight opens a blank form and reports PrefillEmpty`() =
        runTest {
            viewModel.events.test {
                viewModel.startFromText("the quick brown fox jumps over the lazy dog")

                assertThat(awaitItem()).isEqualTo(FlightFormEvent.PrefillEmpty)
                val state = viewModel.formState.value
                assertThat(state.copy(passengers = emptyList())).isEqualTo(FlightFormState(passengers = emptyList()))
                assertThat(state.passengers.single().hasInfo).isFalse()
            }
        }

    @Test
    fun `a text-prefilled flight saves its terminal and is refused as a duplicate the second time`() =
        runTest {
            viewModel.startFromText(AirlineSmsRulesFixture.read("akasa-1.txt"))
            var saved: String? = null
            viewModel.save { saved = it }
            val journey = repository.getFlight(saved!!)!!
            assertThat(journey.airlineIata).isEqualTo("QP")
            assertThat(journey.flightNumber).isEqualTo("1421")
            assertThat(journey.date).isEqualTo(LocalDate.of(2026, 5, 29))
            assertThat(journey.depTerminal).isEqualTo("1")

            viewModel.events.test {
                viewModel.startFromText(AirlineSmsRulesFixture.read("akasa-1.txt"))
                viewModel.save { error("must not save a duplicate") }

                assertThat(awaitItem()).isEqualTo(FlightFormEvent.DuplicateFlight(existingFlightId = journey.id))
                assertThat(repository.savedIds).hasSize(1)
            }
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
                it.copy(airlineIata = "6E", flightNumber = "2345", dateText = "2026-09-25").withSeat("14A")
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
                    flights =
                        listOf(
                            ExtractedFlight(
                                pnr = ExtractedField.of("AB1CD2", ExtractionConfidence.HIGH),
                                carrier = ExtractedField.of("AI", ExtractionConfidence.HIGH),
                                flightNumber = ExtractedField.of("101", ExtractionConfidence.HIGH),
                                fromAirport = ExtractedField.of("DEL", ExtractionConfidence.HIGH),
                                toAirport = ExtractedField.of("FCO", ExtractionConfidence.HIGH),
                                flightDate = ExtractedField.of("2026-09-20", ExtractionConfidence.HIGH),
                            ),
                        ),
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
                    flights =
                        listOf(
                            ExtractedFlight(
                                carrier = ExtractedField.of("AI", ExtractionConfidence.HIGH),
                                flightNumber = ExtractedField.of("101", ExtractionConfidence.HIGH),
                                flightDate = ExtractedField.of("2026-09-20", ExtractionConfidence.HIGH),
                            ),
                        ),
                    source = BoardingPassSource.BARCODE,
                )
            importer.storedPath = "/data/passes/stored.pdf"
            viewModel.startFromBoardingPass("content://picked/pass")

            var saved: String? = null
            viewModel.save { saved = it }

            // ADR-046: the file is keyed by the PASSENGER (row 1) and mirrored onto the flight row.
            val passenger = repository.getPassengers(saved!!).single()
            assertThat(importer.stored).containsExactly("content://picked/pass" to passenger.id)
            assertThat(passenger.boardingPassPath).isEqualTo("/data/passes/stored.pdf")
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
            viewModel.update { it.withSeat("1A") }

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
    fun `single-flight booking confirmation import prefills the form with source and confidence`() =
        runTest {
            bookingImporter.extraction =
                BookingConfirmationExtraction(
                    flights =
                        listOf(
                            ExtractedFlight(
                                pnr = ExtractedField.of("HJK92L", ExtractionConfidence.HIGH),
                                carrier = ExtractedField.of("AI", ExtractionConfidence.HIGH),
                                flightNumber = ExtractedField.of("503", ExtractionConfidence.MEDIUM),
                                fromAirport = ExtractedField.of("COK", ExtractionConfidence.MEDIUM),
                                toAirport = ExtractedField.of("DEL", ExtractionConfidence.MEDIUM),
                                flightDate = ExtractedField.of("2026-09-26", ExtractionConfidence.HIGH),
                                cabinClass = ExtractedField.of("BUSINESS", ExtractionConfidence.HIGH),
                            ),
                        ),
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
            // One segment: no review step, no return-leg hint (ADR-045).
            assertThat(viewModel.importReview.value).isNull()
            assertThat(state.returnLegHint).isFalse()
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
                    flights =
                        listOf(
                            ExtractedFlight(
                                carrier = ExtractedField.of("AI", ExtractionConfidence.HIGH),
                                flightNumber = ExtractedField.of("503", ExtractionConfidence.HIGH),
                                flightDate = ExtractedField.of("2026-09-26", ExtractionConfidence.HIGH),
                            ),
                        ),
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
                    flights =
                        listOf(
                            ExtractedFlight(
                                carrier = ExtractedField.of("6E", ExtractionConfidence.HIGH),
                                flightNumber = ExtractedField.of("6114", ExtractionConfidence.HIGH),
                                flightDate = ExtractedField.of("2026-11-05", ExtractionConfidence.HIGH),
                            ),
                        ),
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

    // ---- flight de-duplication (ADR-025) ----

    private val flightDate: LocalDate = LocalDate.parse("2026-09-25")

    private fun fillNewFlight(
        airline: String,
        number: String,
        date: String = flightDate.toString(),
    ) {
        viewModel.startBlank()
        viewModel.update { it.copy(airlineIata = airline, flightNumber = number, dateText = date) }
    }

    @Test
    fun `new flight duplicating a live journey is refused with a DuplicateFlight event and writes nothing`() =
        runTest {
            val existing = Fixtures.flightJourney(airlineIata = "AI", flightNumber = "101", date = flightDate)
            repository.seed(existing)
            fillNewFlight("AI", "101")

            var saved: String? = null
            viewModel.events.test {
                viewModel.save { saved = it }
                val event = awaitItem() as FlightFormEvent.DuplicateFlight
                assertThat(event.existingFlightId).isEqualTo(existing.id)
            }
            assertThat(saved).isNull()
            assertThat(repository.savedIds).isEmpty()
            assertThat(importer.stored).isEmpty()
            assertThat(viewModel.isBusy.value).isFalse()
        }

    @Test
    fun `duplicate check is airline case-insensitive and includes archived journeys`() =
        runTest {
            val existing = Fixtures.flightJourney(airlineIata = "AI", flightNumber = "101", date = flightDate, archived = true)
            repository.seed(existing)
            fillNewFlight(" ai", "101")

            viewModel.events.test {
                viewModel.save {}
                assertThat((awaitItem() as FlightFormEvent.DuplicateFlight).existingFlightId).isEqualTo(existing.id)
            }
            assertThat(repository.savedIds).isEmpty()
        }

    @Test
    fun `duplicate check ignores leading zeros in the flight number (AI 0101 is AI 101)`() =
        runTest {
            val existing = Fixtures.flightJourney(airlineIata = "AI", flightNumber = "101", date = flightDate)
            repository.seed(existing)
            fillNewFlight("AI", "0101")

            viewModel.events.test {
                viewModel.save {}
                assertThat((awaitItem() as FlightFormEvent.DuplicateFlight).existingFlightId).isEqualTo(existing.id)
            }
            assertThat(repository.savedIds).isEmpty()
        }

    @Test
    fun `boarding-pass import with a stripped number still trips on a zero-padded manual entry`() =
        runTest {
            val existing = Fixtures.flightJourney(airlineIata = "AI", flightNumber = "0101", date = flightDate)
            repository.seed(existing)
            importer.extraction =
                BoardingPassExtraction(
                    flights =
                        listOf(
                            ExtractedFlight(
                                carrier = ExtractedField.of("AI", ExtractionConfidence.HIGH),
                                flightNumber = ExtractedField.of("101", ExtractionConfidence.HIGH),
                                flightDate = ExtractedField.of(flightDate.toString(), ExtractionConfidence.HIGH),
                            ),
                        ),
                    source = BoardingPassSource.BARCODE,
                )
            viewModel.startFromBoardingPass("content://picked/pass")

            viewModel.events.test {
                viewModel.save {}
                assertThat((awaitItem() as FlightFormEvent.DuplicateFlight).existingFlightId).isEqualTo(existing.id)
            }
            assertThat(repository.savedIds).isEmpty()
            assertThat(importer.stored).isEmpty()
        }

    @Test
    fun `same flight on a different date is not a duplicate`() =
        runTest {
            repository.seed(Fixtures.flightJourney(airlineIata = "AI", flightNumber = "101", date = flightDate))
            fillNewFlight("AI", "101", date = flightDate.plusDays(1).toString())

            var saved: String? = null
            viewModel.save { saved = it }

            assertThat(saved).isNotNull()
            assertThat(repository.savedIds).containsExactly(saved)
        }

    @Test
    fun `editing a journey keeps its own identity without tripping the duplicate check`() =
        runTest {
            val journey = Fixtures.flightJourney(airlineIata = "AI", flightNumber = "101", date = flightDate, seat = "1A")
            repository.seed(journey)
            viewModel.startEdit(journey.id)
            viewModel.update { it.withSeat("2B") }

            var saved: String? = null
            viewModel.save { saved = it }

            assertThat(saved).isEqualTo(journey.id)
            assertThat(repository.getFlight(journey.id)!!.seat).isEqualTo("2B")
        }

    @Test
    fun `editing a journey onto ANOTHER journey's identity is refused`() =
        runTest {
            val other = Fixtures.flightJourney(airlineIata = "AI", flightNumber = "101", date = flightDate)
            val journey = Fixtures.flightJourney(airlineIata = "6E", flightNumber = "2345", date = flightDate)
            repository.seed(other, journey)
            viewModel.startEdit(journey.id)
            viewModel.update { it.copy(airlineIata = "AI", flightNumber = "101") }

            var saved: String? = null
            viewModel.events.test {
                viewModel.save { saved = it }
                assertThat((awaitItem() as FlightFormEvent.DuplicateFlight).existingFlightId).isEqualTo(other.id)
            }
            assertThat(saved).isNull()
            assertThat(repository.savedIds).isEmpty()
            assertThat(repository.getFlight(journey.id)!!.airlineIata).isEqualTo("6E")
        }

    @Test
    fun `a tombstoned journey's identity can be added again`() =
        runTest {
            val deleted = Fixtures.flightJourney(airlineIata = "AI", flightNumber = "101", date = flightDate)
            repository.seed(deleted)
            repository.delete(deleted.id)
            fillNewFlight("AI", "101")

            var saved: String? = null
            viewModel.save { saved = it }

            assertThat(saved).isNotNull()
            assertThat(saved).isNotEqualTo(deleted.id)
            assertThat(repository.savedIds).containsExactly(saved)
        }

    // ---- multi-flight import (ADR-045) ----

    private fun segment(
        number: String,
        from: String,
        to: String,
        date: String,
        pnr: String = "R87T5G",
        seat: String? = null,
    ) = ExtractedFlight(
        carrier = ExtractedField.of("IX", ExtractionConfidence.MEDIUM),
        flightNumber = ExtractedField.of(number, ExtractionConfidence.MEDIUM),
        fromAirport = ExtractedField.of(from, ExtractionConfidence.MEDIUM),
        toAirport = ExtractedField.of(to, ExtractionConfidence.MEDIUM),
        flightDate = ExtractedField.of(date, ExtractionConfidence.MEDIUM),
        depTime = ExtractedField.of("10:30", ExtractionConfidence.MEDIUM),
        depTerminal = ExtractedField.of("2", ExtractionConfidence.MEDIUM),
        pnr = ExtractedField.of(pnr, ExtractionConfidence.HIGH),
        seat = ExtractedField.of(seat, ExtractionConfidence.MEDIUM),
    )

    private val onward = segment("2019", "BLR", "LKO", "2026-10-29", seat = "12A")
    private val back = segment("1542", "LKO", "BLR", "2026-10-31", pnr = "D2FE9V", seat = "10A")

    @Test
    fun `a single-segment boarding pass opens the form with no review step`() =
        runTest {
            importer.extraction = BoardingPassExtraction(flights = listOf(onward), source = BoardingPassSource.OCR_TEXT)

            viewModel.startFromBoardingPass("content://picked/one")

            assertThat(viewModel.importReview.value).isNull()
            assertThat(viewModel.formState.value.flightNumber).isEqualTo("2019")
            assertThat(viewModel.formState.value.depTimeText).isEqualTo("10:30")
            assertThat(viewModel.formState.value.pendingPassUri).isEqualTo("content://picked/one")
        }

    @Test
    fun `a two-segment file opens the review step with every segment selected and the form untouched`() =
        runTest {
            importer.extraction = BoardingPassExtraction(flights = listOf(onward, back), source = BoardingPassSource.OCR_TEXT)

            viewModel.startFromBoardingPass("content://picked/two")

            val review = viewModel.importReview.value!!
            assertThat(review.kind).isEqualTo(FlightImportKind.BOARDING_PASS)
            assertThat(review.items).hasSize(2)
            assertThat(review.items.all { it.selected && it.isComplete }).isTrue()
            assertThat(review.items.map { it.form.flightNumber }).containsExactly("2019", "1542").inOrder()
            assertThat(review.items[1].form.pnr).isEqualTo("D2FE9V")
            assertThat(viewModel.formState.value.flightNumber).isEmpty()
            assertThat(repository.savedIds).isEmpty()
        }

    @Test
    fun `confirming complete segments saves them all with a pass copy per flight`() =
        runTest {
            importer.extraction = BoardingPassExtraction(flights = listOf(onward, back), source = BoardingPassSource.BARCODE)
            importer.perFlightPaths = true
            viewModel.startFromBoardingPass("content://picked/two")

            viewModel.events.test {
                viewModel.confirmReview()
                val outcome = (awaitItem() as FlightFormEvent.BatchImported).outcome
                assertThat(outcome.savedIds).hasSize(2)
                assertThat(outcome.skippedExistingIds).isEmpty()
                assertThat(viewModel.importReview.value).isNull()
                assertThat(viewModel.isBusy.value).isFalse()

                val flights = outcome.savedIds.map { repository.getFlight(it)!! }
                assertThat(flights.map { it.flightNumber }).containsExactly("2019", "1542").inOrder()
                assertThat(flights.map { it.seat }).containsExactly("12A", "10A").inOrder()
                assertThat(flights.map { it.depTerminal }).containsExactly("2", "2")
                assertThat(flights.map { it.schedDep }.all { it != null }).isTrue()
                // The file is stored once PER flight, under that flight's passenger's id (ADR-046).
                val passengerIds = outcome.savedIds.map { repository.getPassengers(it).single().id }
                assertThat(importer.stored.map { it.second }).containsExactlyElementsIn(passengerIds).inOrder()
                assertThat(flights.map { it.boardingPassPath }.toSet()).hasSize(2)
                // Deleting one flight leaves the other's copy referenced.
                repository.delete(outcome.savedIds[0])
                assertThat(
                    repository.getFlight(outcome.savedIds[1])!!.boardingPassPath,
                ).isEqualTo("/data/passes/${passengerIds[1]}.jpg")
            }
        }

    @Test
    fun `a booking confirmation with two flights attaches the file to each saved flight`() =
        runTest {
            bookingImporter.extraction =
                BookingConfirmationExtraction(flights = listOf(onward, back), source = BookingConfirmationSource.OCR_TEXT)
            viewModel.startFromBookingConfirmation("content://picked/booking")
            assertThat(viewModel.importReview.value!!.kind).isEqualTo(FlightImportKind.BOOKING_CONFIRMATION)

            viewModel.events.test {
                viewModel.confirmReview()
                val outcome = (awaitItem() as FlightFormEvent.BatchImported).outcome
                assertThat(outcome.savedIds).hasSize(2)
                assertThat(bookingImporter.attached.map { it.second }).containsExactlyElementsIn(outcome.savedIds)
                for (id in outcome.savedIds) {
                    assertThat(bookingImporter.attachmentRepository.observeForOwner(AttachmentOwnerType.FLIGHT, id).first()).hasSize(1)
                    assertThat(repository.getFlight(id)!!.boardingPassPath).isNull()
                }
                assertThat(importer.stored).isEmpty()
            }
        }

    @Test
    fun `duplicates are skipped per segment and the rest still save`() =
        runTest {
            // The existing journey already carries the segment's passenger (same seat, no name).
            val existing =
                Fixtures.flightJourney(
                    airlineIata = "IX",
                    flightNumber = "2019",
                    date = LocalDate.parse("2026-10-29"),
                    seat = "12A",
                )
            repository.seed(existing)
            importer.extraction = BoardingPassExtraction(flights = listOf(onward, back), source = BoardingPassSource.OCR_TEXT)
            viewModel.startFromBoardingPass("content://picked/two")

            viewModel.events.test {
                viewModel.confirmReview()
                val outcome = (awaitItem() as FlightFormEvent.BatchImported).outcome
                assertThat(outcome.skippedExistingIds).containsExactly(existing.id)
                assertThat(outcome.savedIds).hasSize(1)
                assertThat(repository.getFlight(outcome.savedIds.single())!!.flightNumber).isEqualTo("1542")
                assertThat(outcome.focusFlightId).isEqualTo(outcome.savedIds.single())
                // The duplicate's passenger (seat 12A, no pass yet) gains THIS pass — nothing else is written for it.
                val legacy = repository.getPassengers(existing.id).single()
                assertThat(legacy.boardingPassPath).isNotNull()
                assertThat(importer.stored.map { it.second })
                    .containsExactly(legacy.id, repository.getPassengers(outcome.savedIds.single()).single().id)
                    .inOrder()
                assertThat(repository.getFlight(existing.id)!!.boardingPassPath).isEqualTo(legacy.boardingPassPath)
            }
        }

    @Test
    fun `a segment for an existing flight with a NEW seat adds a passenger instead of skipping (ADR-046)`() =
        runTest {
            val existing =
                Fixtures.flightJourney(
                    airlineIata = "IX",
                    flightNumber = "2019",
                    date = LocalDate.parse("2026-10-29"),
                    seat = "12B",
                )
            repository.seed(existing)
            importer.extraction = BoardingPassExtraction(flights = listOf(onward), source = BoardingPassSource.BARCODE)
            importer.perFlightPaths = true
            viewModel.startFromBoardingPass("content://picked/one")

            viewModel.events.test {
                viewModel.save { error("onSaved must not fire: nothing new was saved, a passenger was added") }
                val outcome = (awaitItem() as FlightFormEvent.BatchImported).outcome
                assertThat(outcome.savedIds).isEmpty()
                assertThat(outcome.skippedExistingIds).isEmpty()
                assertThat(outcome.addedPassengers).containsExactly(AddedPassenger(existing.id, "12A", "IX 2019"))
                assertThat(outcome.isSinglePassengerAdd).isTrue()
                assertThat(outcome.focusFlightId).isEqualTo(existing.id)
            }
            val passengers = repository.getPassengers(existing.id)
            // The legacy mirror seat became passenger #1; the new seat is passenger #2 with ITS OWN pass copy.
            assertThat(passengers.map { it.seat }).containsExactly("12B", "12A").inOrder()
            assertThat(passengers[0].boardingPassPath).isNull()
            assertThat(passengers[1].boardingPassPath).isEqualTo("/data/passes/${passengers[1].id}.jpg")
            assertThat(importer.stored.map { it.second }).containsExactly(passengers[1].id)
            assertThat(repository.getFlight(existing.id)!!.seat).isEqualTo("12B")
            assertThat(repository.getFlight(existing.id)!!.boardingPassPath).isEqualTo(passengers[1].boardingPassPath)
        }

    @Test
    fun `two passes for the same flight from two files become one flight with two named passengers`() =
        runTest {
            val prakash = onward.copy(passengerName = ExtractedField.of("PRAKASH KUMAR", ExtractionConfidence.HIGH))
            val bandana =
                onward.copy(
                    passengerName = ExtractedField.of("BANDANA KUMARI", ExtractionConfidence.HIGH),
                    seat = ExtractedField.of("12B", ExtractionConfidence.HIGH),
                )
            importer.perFlightPaths = true

            importer.extraction = BoardingPassExtraction(flights = listOf(prakash), source = BoardingPassSource.BARCODE)
            viewModel.startFromBoardingPass("content://picked/prakash")
            var saved: String? = null
            viewModel.save { saved = it }
            val flightId = saved!!

            importer.extraction = BoardingPassExtraction(flights = listOf(bandana), source = BoardingPassSource.BARCODE)
            viewModel.startFromBoardingPass("content://picked/bandana")
            viewModel.events.test {
                viewModel.save { error("the second pass must join the first flight") }
                val outcome = (awaitItem() as FlightFormEvent.BatchImported).outcome
                assertThat(outcome.addedPassengers).containsExactly(AddedPassenger(flightId, "BANDANA KUMARI", "IX 2019"))
            }

            assertThat(repository.observeActive().first()).hasSize(1)
            val passengers = repository.getPassengers(flightId)
            assertThat(passengers.map { it.name to it.seat }).containsExactly("PRAKASH KUMAR" to "12A", "BANDANA KUMARI" to "12B").inOrder()
            assertThat(passengers.map { it.boardingPassPath })
                .containsExactly(
                    "/data/passes/${passengers[0].id}.jpg",
                    "/data/passes/${passengers[1].id}.jpg",
                ).inOrder()
            assertThat(importer.stored.map { it.first }).containsExactly("content://picked/prakash", "content://picked/bandana").inOrder()

            // The same pass again: already on board → the ADR-025 notice, nothing written.
            viewModel.startFromBoardingPass("content://picked/bandana-again")
            viewModel.events.test {
                viewModel.save { error("not saved") }
                assertThat((awaitItem() as FlightFormEvent.DuplicateFlight).existingFlightId).isEqualTo(flightId)
            }
            assertThat(repository.getPassengers(flightId)).hasSize(2)
            assertThat(importer.stored).hasSize(2)
        }

    @Test
    fun `a different name on the same seat is a new passenger, and a named pass completes a nameless legacy row`() =
        runTest {
            val flightId = "ix1542"
            val existing =
                Fixtures.flightJourney(
                    id = flightId,
                    airlineIata = "IX",
                    flightNumber = "1542",
                    date = LocalDate.parse("2026-10-31"),
                )
            repository.seed(existing)
            repository.seedPassengers(Fixtures.flightPassenger(flightId = flightId, name = "", seat = "10A", boardingPassPath = null))

            // Bandana, seat 10A, pass → claims the nameless 10A row (name + pass filled, no new row).
            importer.extraction =
                BoardingPassExtraction(
                    flights = listOf(back.copy(passengerName = ExtractedField.of("BANDANA KUMARI", ExtractionConfidence.HIGH))),
                    source = BoardingPassSource.OCR_TEXT,
                )
            viewModel.startFromBoardingPass("content://picked/bandana")
            viewModel.events.test {
                viewModel.save { error("not a new flight") }
                assertThat((awaitItem() as FlightFormEvent.DuplicateFlight).existingFlightId).isEqualTo(flightId)
            }
            val claimed = repository.getPassengers(flightId).single()
            assertThat(claimed.name).isEqualTo("BANDANA KUMARI")
            assertThat(claimed.boardingPassPath).isEqualTo("/data/fake/pass.jpg")

            // Prakash, ALSO seat 10A (the image had him there): a different name wins → second passenger.
            importer.extraction =
                BoardingPassExtraction(
                    flights = listOf(back.copy(passengerName = ExtractedField.of("PRAKASH KUMAR", ExtractionConfidence.HIGH))),
                    source = BoardingPassSource.OCR_TEXT,
                )
            viewModel.startFromBoardingPass("content://picked/prakash")
            viewModel.events.test {
                viewModel.save { error("not a new flight") }
                val outcome = (awaitItem() as FlightFormEvent.BatchImported).outcome
                assertThat(outcome.addedPassengers.single().label).isEqualTo("PRAKASH KUMAR")
            }
            assertThat(repository.getPassengers(flightId).map { it.name }).containsExactly("BANDANA KUMARI", "PRAKASH KUMAR").inOrder()
        }

    @Test
    fun `a manual save naming nobody on an existing flight is still refused as a duplicate`() =
        runTest {
            val existing = Fixtures.flightJourney(airlineIata = "AI", flightNumber = "101", date = flightDate)
            repository.seed(existing)
            fillNewFlight("AI", "101")

            viewModel.events.test {
                viewModel.save { error("not saved") }
                assertThat((awaitItem() as FlightFormEvent.DuplicateFlight).existingFlightId).isEqualTo(existing.id)
            }
            assertThat(repository.savedIds).isEmpty()
        }

    @Test
    fun `passenger rows add, edit and remove - save writes the rows and edit reloads them`() =
        runTest {
            viewModel.startBlank()
            assertThat(viewModel.formState.value.passengers).hasSize(1)
            viewModel.addPassengerRow()
            val rows = viewModel.formState.value.passengers
            assertThat(rows).hasSize(2)
            viewModel.updatePassengerRow(rows[0].rowKey) { it.copy(name = "A", seat = "1a") }
            viewModel.updatePassengerRow(rows[1].rowKey) { it.copy(name = "B", seat = "1B") }
            viewModel.addPassengerRow()
            viewModel.removePassengerRow(
                viewModel.formState.value.passengers[2]
                    .rowKey,
            )
            viewModel.update { it.copy(airlineIata = "6E", flightNumber = "2345", dateText = "2026-09-25") }

            var saved: String? = null
            viewModel.save { saved = it }

            val passengers = repository.getPassengers(saved!!)
            assertThat(passengers.map { it.name to it.seat }).containsExactly("A" to "1a", "B" to "1B").inOrder()
            assertThat(passengers.map { it.id }).containsExactly(rows[0].rowKey, rows[1].rowKey).inOrder()
            assertThat(repository.getFlight(saved!!)!!.seat).isEqualTo("1a")

            // Edit: rows reload with their ids; removing B tombstones B and keeps A's id.
            viewModel.startEdit(saved!!)
            assertThat(
                viewModel.formState.value.passengers
                    .map { it.rowKey },
            ).containsExactly(rows[0].rowKey, rows[1].rowKey).inOrder()
            viewModel.removePassengerRow(rows[1].rowKey)
            viewModel.save { }
            assertThat(repository.getPassengers(saved!!).map { it.id }).containsExactly(rows[0].rowKey)

            // Removing the last row leaves one blank row, which saves as "no passengers".
            viewModel.startEdit(saved!!)
            viewModel.removePassengerRow(rows[0].rowKey)
            assertThat(
                viewModel.formState.value.passengers
                    .single()
                    .hasInfo,
            ).isFalse()
            viewModel.save { }
            assertThat(repository.getPassengers(saved!!)).isEmpty()
            assertThat(repository.getFlight(saved!!)!!.seat).isEmpty()
        }

    @Test
    fun `re-importing the same file skips every segment`() =
        runTest {
            importer.extraction = BoardingPassExtraction(flights = listOf(onward, back), source = BoardingPassSource.OCR_TEXT)
            viewModel.startFromBoardingPass("content://picked/two")
            viewModel.events.test {
                viewModel.confirmReview()
                assertThat((awaitItem() as FlightFormEvent.BatchImported).outcome.savedIds).hasSize(2)
            }

            viewModel.startFromBoardingPass("content://picked/two")
            viewModel.events.test {
                viewModel.confirmReview()
                val outcome = (awaitItem() as FlightFormEvent.BatchImported).outcome
                assertThat(outcome.savedIds).isEmpty()
                assertThat(outcome.skippedExistingIds).hasSize(2)
                assertThat(repository.savedIds).hasSize(2)
            }
        }

    @Test
    fun `an unticked segment is not saved`() =
        runTest {
            importer.extraction = BoardingPassExtraction(flights = listOf(onward, back), source = BoardingPassSource.OCR_TEXT)
            viewModel.startFromBoardingPass("content://picked/two")

            viewModel.toggleReviewItem(0)
            assertThat(viewModel.importReview.value!!.selectedCount).isEqualTo(1)
            viewModel.events.test {
                viewModel.confirmReview()
                val outcome = (awaitItem() as FlightFormEvent.BatchImported).outcome
                assertThat(outcome.savedIds).hasSize(1)
                assertThat(repository.getFlight(outcome.savedIds.single())!!.flightNumber).isEqualTo("1542")
            }
        }

    @Test
    fun `dismissing the review writes nothing`() =
        runTest {
            importer.extraction = BoardingPassExtraction(flights = listOf(onward, back), source = BoardingPassSource.OCR_TEXT)
            viewModel.startFromBoardingPass("content://picked/two")

            viewModel.dismissReview()

            assertThat(viewModel.importReview.value).isNull()
            assertThat(repository.savedIds).isEmpty()
            assertThat(importer.stored).isEmpty()
        }

    @Test
    fun `an incomplete segment walks the selection through the form and Save advances`() =
        runTest {
            val noRoute = back.copy(fromAirport = ExtractedField.EMPTY, toAirport = ExtractedField.EMPTY)
            importer.extraction = BoardingPassExtraction(flights = listOf(onward, noRoute), source = BoardingPassSource.OCR_TEXT)
            importer.perFlightPaths = true
            viewModel.startFromBoardingPass("content://picked/two")
            assertThat(
                viewModel.importReview.value!!
                    .items
                    .map { it.isComplete },
            ).containsExactly(true, false).inOrder()

            viewModel.events.test {
                viewModel.confirmReview()
                expectNoEvents()

                // Walk-through: flight 1 of 2 is on the form, nothing written yet.
                var state = viewModel.formState.value
                assertThat(state.walkthrough).isEqualTo(WalkthroughProgress(index = 1, total = 2))
                assertThat(state.flightNumber).isEqualTo("2019")
                assertThat(repository.savedIds).isEmpty()

                // Save writes flight 1 and advances; onSaved is NOT called mid-walk.
                var savedCallback: String? = null
                viewModel.save { savedCallback = it }
                assertThat(savedCallback).isNull()
                assertThat(repository.savedIds).hasSize(1)
                state = viewModel.formState.value
                assertThat(state.walkthrough).isEqualTo(WalkthroughProgress(index = 2, total = 2))
                assertThat(state.flightNumber).isEqualTo("1542")
                assertThat(state.depAirport).isEmpty()

                // The user fills the missing route and saves the last one → BatchImported.
                viewModel.update { it.copy(depAirport = "LKO", arrAirport = "BLR") }
                viewModel.save { savedCallback = it }
                val outcome = (awaitItem() as FlightFormEvent.BatchImported).outcome
                assertThat(savedCallback).isNull()
                assertThat(outcome.savedIds).hasSize(2)
                assertThat(repository.getFlight(outcome.savedIds[1])!!.depAirport).isEqualTo("LKO")
                assertThat(importer.stored.map { it.second })
                    .containsExactlyElementsIn(outcome.savedIds.map { repository.getPassengers(it).single().id })
                    .inOrder()
                assertThat(viewModel.formState.value.walkthrough).isNull()
            }
        }

    @Test
    fun `a duplicate met during the walk-through is skipped and the walk advances`() =
        runTest {
            val existing =
                Fixtures.flightJourney(
                    airlineIata = "IX",
                    flightNumber = "2019",
                    date = LocalDate.parse("2026-10-29"),
                    seat = "12A",
                )
            repository.seed(existing)
            val noRoute = back.copy(fromAirport = ExtractedField.EMPTY, toAirport = ExtractedField.EMPTY)
            importer.extraction = BoardingPassExtraction(flights = listOf(onward, noRoute), source = BoardingPassSource.OCR_TEXT)
            viewModel.startFromBoardingPass("content://picked/two")

            viewModel.events.test {
                viewModel.confirmReview()
                viewModel.save { error("onSaved must not fire mid-walk") }
                expectNoEvents()
                assertThat(viewModel.formState.value.walkthrough).isEqualTo(WalkthroughProgress(index = 2, total = 2))

                viewModel.save { error("onSaved must not fire at the end of a walk either") }
                val outcome = (awaitItem() as FlightFormEvent.BatchImported).outcome
                assertThat(outcome.skippedExistingIds).containsExactly(existing.id)
                assertThat(outcome.savedIds).hasSize(1)
            }
        }

    @Test
    fun `an invalid walk-through form shows errors and does not advance`() =
        runTest {
            val noDate = back.copy(flightDate = ExtractedField.EMPTY)
            importer.extraction = BoardingPassExtraction(flights = listOf(noDate, onward), source = BoardingPassSource.OCR_TEXT)
            viewModel.startFromBoardingPass("content://picked/two")
            viewModel.confirmReview()

            viewModel.save { error("not saved") }

            assertThat(viewModel.formState.value.errors).contains(FlightFormError.DATE_INVALID)
            assertThat(viewModel.formState.value.walkthrough).isEqualTo(WalkthroughProgress(index = 1, total = 2))
            assertThat(repository.savedIds).isEmpty()
        }

    private companion object {
        /** Row 1's seat — what the pre-ADR-046 single Seat field was. */
        fun FlightFormState.withSeat(seat: String): FlightFormState =
            copy(passengers = listOf((passengers.firstOrNull() ?: PassengerFormRow()).copy(seat = seat)) + passengers.drop(1))
    }
}
