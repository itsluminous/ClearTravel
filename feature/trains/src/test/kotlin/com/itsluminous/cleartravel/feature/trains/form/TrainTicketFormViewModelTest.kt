package com.itsluminous.cleartravel.feature.trains.form

import android.net.Uri
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.ocr.ExtractionConfidence
import com.itsluminous.cleartravel.core.ocr.model.ExtractedField
import com.itsluminous.cleartravel.core.ocr.model.PassengerExtraction
import com.itsluminous.cleartravel.core.ocr.model.TrainTicketExtraction
import com.itsluminous.cleartravel.core.testing.Fixtures
import com.itsluminous.cleartravel.core.testing.MainDispatcherRule
import com.itsluminous.cleartravel.feature.trains.FakeTrainRepository
import com.itsluminous.cleartravel.feature.trains.prefill.TrainPrefillSource
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

class TrainTicketFormViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = FakeTrainRepository(now = { Fixtures.NOW })

    private class FakePrefillSource(
        var extraction: TrainTicketExtraction = TrainTicketExtraction.EMPTY,
    ) : TrainPrefillSource {
        override suspend fun fromUri(uri: Uri): TrainTicketExtraction = extraction

        override fun fromText(text: String): TrainTicketExtraction = extraction
    }

    private val prefillSource = FakePrefillSource()

    private fun viewModel() = TrainTicketFormViewModel(repository, prefillSource)

    private val richExtraction =
        TrainTicketExtraction(
            pnr = ExtractedField.of("8524317690", ExtractionConfidence.HIGH),
            trainNumber = ExtractedField.of("12951", ExtractionConfidence.HIGH),
            trainName = ExtractedField.of("MUMBAI RAJDHANI", ExtractionConfidence.MEDIUM),
            journeyDate = ExtractedField.of("2026-09-25", ExtractionConfidence.HIGH),
            fromStation = ExtractedField.of("MMCT", ExtractionConfidence.MEDIUM),
            toStation = ExtractedField.of("NDLS", ExtractionConfidence.MEDIUM),
            travelClass = ExtractedField.of("3A", ExtractionConfidence.LOW),
            passengers =
                listOf(
                    PassengerExtraction(
                        name = ExtractedField.of("ASHA RAO", ExtractionConfidence.LOW),
                        coach = ExtractedField.of("B4", ExtractionConfidence.HIGH),
                        berth = ExtractedField.of("32", ExtractionConfidence.HIGH),
                        bookingStatus = ExtractedField.of("CNF", ExtractionConfidence.HIGH),
                    ),
                ),
        )

    @Test
    fun `starts blank with one empty passenger row`() {
        val vm = viewModel()

        val state = vm.uiState.value
        assertThat(state.pnr).isEmpty()
        assertThat(state.passengers).hasSize(1)
        assertThat(state.isEdit).isFalse()
    }

    @Test
    fun `invalid pnr blocks save and flags the field`() =
        runTest {
            val vm = viewModel()
            vm.onPnrChange("12345")

            vm.save()

            assertThat(vm.uiState.value.pnrError).isTrue()
            assertThat(repository.savedTickets).isEmpty()
        }

    @Test
    fun `valid save persists ticket and passengers with sort order`() =
        runTest {
            val vm = viewModel()
            vm.onPnrChange("8524317690")
            vm.onTrainNumberChange("12951")
            vm.onJourneyDateChange(LocalDate.parse("2026-09-25"))
            vm.updatePassengerRow(
                vm.uiState.value.passengers[0]
                    .rowKey,
            ) { it.copy(name = "ASHA RAO", coach = "B4") }
            vm.addPassengerRow()
            vm.updatePassengerRow(
                vm.uiState.value.passengers[1]
                    .rowKey,
            ) { it.copy(name = "RAVI RAO") }

            vm.events.test {
                vm.save()
                val event = awaitItem()
                assertThat(event).isInstanceOf(TrainFormEvent.Saved::class.java)
            }

            val ticket = repository.savedTickets.single()
            assertThat(ticket.pnr).isEqualTo("8524317690")
            assertThat(ticket.trainNumber).isEqualTo("12951")
            val passengers = repository.savedPassengerBatches.single()
            assertThat(passengers.map { it.name }).containsExactly("ASHA RAO", "RAVI RAO").inOrder()
            assertThat(passengers.map { it.sortOrder }).containsExactly(0, 1).inOrder()
            assertThat(passengers.all { it.ticketId == ticket.id }).isTrue()
        }

    @Test
    fun `pnr-only quick add asks the host to open the PNR check`() =
        runTest {
            val vm = viewModel()
            vm.onPnrChange("8524317690")

            vm.events.test {
                vm.save()
                val event = awaitItem() as TrainFormEvent.Saved
                assertThat(event.openPnrCheck).isTrue()
                assertThat(event.pnr).isEqualTo("8524317690")
            }
        }

    @Test
    fun `save with any journey detail does not open the PNR check`() =
        runTest {
            val vm = viewModel()
            vm.onPnrChange("8524317690")
            vm.onTrainNumberChange("12951")

            vm.events.test {
                vm.save()
                val event = awaitItem() as TrainFormEvent.Saved
                assertThat(event.openPnrCheck).isFalse()
            }
        }

    @Test
    fun `blank passenger rows are not persisted`() =
        runTest {
            val vm = viewModel()
            vm.onPnrChange("8524317690")

            vm.save()

            assertThat(repository.savedPassengerBatches.single()).isEmpty()
        }

    @Test
    fun `start with pnr carries only the pnr into an otherwise blank form`() {
        val vm = viewModel()
        vm.startFromText("irctc sms")

        vm.startWithPnr(" 8553674906 ")

        val state = vm.uiState.value
        assertThat(state.pnr).isEqualTo("8553674906")
        assertThat(state.trainNumber).isEmpty()
        assertThat(state.passengers).hasSize(1)
        assertThat(state.prefilled).isTrue()
        assertThat(state.isEdit).isFalse()
    }

    @Test
    fun `prefill from text applies fields and records confidences`() {
        prefillSource.extraction = richExtraction
        val vm = viewModel()

        vm.startFromText("irctc sms")

        val state = vm.uiState.value
        assertThat(state.pnr).isEqualTo("8524317690")
        assertThat(state.trainNumber).isEqualTo("12951")
        assertThat(state.journeyDate).isEqualTo(LocalDate.parse("2026-09-25"))
        assertThat(state.fromStation).isEqualTo("MMCT")
        assertThat(state.toStation).isEqualTo("NDLS")
        assertThat(state.travelClass).isEqualTo("3A")
        assertThat(state.prefilled).isTrue()
        assertThat(state.confidences[TrainFormField.PNR]).isEqualTo(ExtractionConfidence.HIGH)
        assertThat(state.confidences[TrainFormField.TRAVEL_CLASS]).isEqualTo(ExtractionConfidence.LOW)
        assertThat(state.lowConfidence(TrainFormField.TRAVEL_CLASS)).isTrue()
        assertThat(state.lowConfidence(TrainFormField.PNR)).isFalse()
    }

    @Test
    fun `prefill fills passenger rows and marks low-confidence names`() {
        prefillSource.extraction = richExtraction
        val vm = viewModel()

        vm.startFromText("irctc sms")

        val row =
            vm.uiState.value.passengers
                .single()
        assertThat(row.name).isEqualTo("ASHA RAO")
        assertThat(row.coach).isEqualTo("B4")
        assertThat(row.seatBerth).isEqualTo("32")
        assertThat(row.bookingStatus).isEqualTo("CNF")
        assertThat(row.lowConfidence).isTrue()
    }

    @Test
    fun `empty extraction leaves the form blank and emits a hint event`() =
        runTest {
            prefillSource.extraction = TrainTicketExtraction.EMPTY
            val vm = viewModel()

            vm.events.test {
                vm.startFromText("total garbage")
                assertThat(awaitItem()).isEqualTo(TrainFormEvent.PrefillEmpty)
            }
            assertThat(vm.uiState.value.pnr).isEmpty()
            assertThat(vm.uiState.value.prefilled).isFalse()
        }

    @Test
    fun `unrecognized fields keep their previous values on prefill`() {
        prefillSource.extraction =
            TrainTicketExtraction(pnr = ExtractedField.of("8524317690", ExtractionConfidence.HIGH))
        val vm = viewModel()

        vm.startFromText("pnr-only sms")

        val state = vm.uiState.value
        assertThat(state.pnr).isEqualTo("8524317690")
        assertThat(state.trainNumber).isEmpty()
        assertThat(state.confidences).containsKey(TrainFormField.PNR)
        assertThat(state.confidences).doesNotContainKey(TrainFormField.TRAIN_NUMBER)
    }

    @Test
    fun `editing a row clears its low-confidence marker`() {
        prefillSource.extraction = richExtraction
        val vm = viewModel()
        vm.startFromText("irctc sms")

        val rowKey =
            vm.uiState.value.passengers
                .single()
                .rowKey
        vm.updatePassengerRow(rowKey) { it.copy(name = "Corrected Name") }

        assertThat(
            vm.uiState.value.passengers
                .single()
                .lowConfidence,
        ).isFalse()
    }

    @Test
    fun `start edit loads the existing aggregate`() =
        runTest {
            val ticket = Fixtures.trainTicket()
            val passenger = Fixtures.trainPassenger(ticketId = ticket.id, name = "ASHA RAO")
            repository.seed(ticket, listOf(passenger))
            val vm = viewModel()

            vm.startEdit(ticket.id)

            val state = vm.uiState.value
            assertThat(state.isEdit).isTrue()
            assertThat(state.pnr).isEqualTo(ticket.pnr)
            assertThat(state.passengers.single().name).isEqualTo("ASHA RAO")
            assertThat(state.passengers.single().existingId).isEqualTo(passenger.id)
        }

    @Test
    fun `removing an existing passenger tombstones it on save`() =
        runTest {
            val ticket = Fixtures.trainTicket()
            val keep = Fixtures.trainPassenger(ticketId = ticket.id, name = "KEEP", sortOrder = 0)
            val remove = Fixtures.trainPassenger(ticketId = ticket.id, name = "REMOVE", sortOrder = 1)
            repository.seed(ticket, listOf(keep, remove))
            val vm = viewModel()
            vm.startEdit(ticket.id)

            val removeKey =
                vm.uiState.value.passengers
                    .first { it.name == "REMOVE" }
                    .rowKey
            vm.removePassengerRow(removeKey)
            vm.save()

            val batch = repository.savedPassengerBatches.single()
            val tombstoned = batch.first { it.id == remove.id }
            assertThat(tombstoned.deletedAt).isNotNull()
            val kept = batch.first { it.id == keep.id }
            assertThat(kept.deletedAt).isNull()
        }

    @Test
    fun `edit save keeps the same ticket id`() =
        runTest {
            val ticket = Fixtures.trainTicket()
            repository.seed(ticket)
            val vm = viewModel()
            vm.startEdit(ticket.id)

            vm.onTrainNameChange("Renamed Express")
            vm.save()

            val saved = repository.savedTickets.single()
            assertThat(saved.id).isEqualTo(ticket.id)
            assertThat(saved.trainName).isEqualTo("Renamed Express")
        }

    // ---- PNR de-duplication (ADR-024) ----

    @Test
    fun `new ticket with an existing live PNR is refused with a DuplicatePnr event`() =
        runTest {
            val existing = Fixtures.trainTicket(pnr = "8553674906")
            repository.seed(existing)
            val vm = viewModel()
            vm.onPnrChange("8553674906")
            vm.onTrainNumberChange("20933")

            vm.events.test {
                vm.save()
                val event = awaitItem() as TrainFormEvent.DuplicatePnr
                assertThat(event.existingTicketId).isEqualTo(existing.id)
                assertThat(event.pnr).isEqualTo("8553674906")
            }
            assertThat(repository.savedTickets).isEmpty()
            assertThat(repository.savedPassengerBatches).isEmpty()
            assertThat(vm.uiState.value.saving).isFalse()
        }

    @Test
    fun `duplicate check is whitespace and case insensitive and includes archived tickets`() =
        runTest {
            val existing = Fixtures.trainTicket(pnr = "8553674906", archived = true)
            repository.seed(existing)
            val vm = viewModel()
            vm.onPnrChange("  8553674906 ")

            vm.events.test {
                vm.save()
                assertThat((awaitItem() as TrainFormEvent.DuplicatePnr).existingTicketId).isEqualTo(existing.id)
            }
            assertThat(repository.savedTickets).isEmpty()
        }

    @Test
    fun `editing a ticket keeps its own PNR without tripping the duplicate check`() =
        runTest {
            val ticket = Fixtures.trainTicket(pnr = "8553674906")
            repository.seed(ticket)
            val vm = viewModel()
            vm.startEdit(ticket.id)
            vm.onTrainNameChange("Renamed Express")

            vm.events.test {
                vm.save()
                assertThat((awaitItem() as TrainFormEvent.Saved).ticketId).isEqualTo(ticket.id)
            }
            assertThat(repository.savedTickets.single().trainName).isEqualTo("Renamed Express")
        }

    @Test
    fun `editing a ticket onto ANOTHER ticket's PNR is refused`() =
        runTest {
            val other = Fixtures.trainTicket(pnr = "1111111111")
            val ticket = Fixtures.trainTicket(pnr = "2222222222")
            repository.seed(other)
            repository.seed(ticket)
            val vm = viewModel()
            vm.startEdit(ticket.id)
            vm.onPnrChange("1111111111")

            vm.events.test {
                vm.save()
                assertThat((awaitItem() as TrainFormEvent.DuplicatePnr).existingTicketId).isEqualTo(other.id)
            }
            assertThat(repository.savedTickets).isEmpty()
        }

    @Test
    fun `a tombstoned ticket's PNR can be added again`() =
        runTest {
            val deleted = Fixtures.trainTicket(pnr = "8553674906")
            repository.seed(deleted)
            repository.delete(deleted.id)
            val vm = viewModel()
            vm.onPnrChange("8553674906")
            vm.onTrainNumberChange("20933")

            vm.events.test {
                vm.save()
                val event = awaitItem() as TrainFormEvent.Saved
                assertThat(event.ticketId).isNotEqualTo(deleted.id)
            }
            assertThat(repository.savedTickets.single().pnr).isEqualTo("8553674906")
        }
}
