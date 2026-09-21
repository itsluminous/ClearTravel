package com.itsluminous.cleartravel.ui.intake

import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.ocr.ExtractionConfidence
import com.itsluminous.cleartravel.core.ocr.model.BoardingPassExtraction
import com.itsluminous.cleartravel.core.ocr.model.BoardingPassSource
import com.itsluminous.cleartravel.core.ocr.model.ExtractedField
import com.itsluminous.cleartravel.core.testing.MainDispatcherRule
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class SharedFileIntakeViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private var probeResult = SharedDocProbeResult()
    private var probeCalls = 0

    // A lambda-backed fake (fun interface) rather than a named subclass: keeps the
    // test free of a source supertype so lint's test-source analysis stays simple.
    private val probe =
        SharedDocProbe {
            probeCalls++
            probeResult
        }

    private fun viewModel() = SharedFileIntakeViewModel(probe)

    private val barcodePass =
        SharedDocProbeResult(
            boardingPass =
                BoardingPassExtraction(
                    pnr = ExtractedField.of("ABC123", ExtractionConfidence.HIGH),
                    source = BoardingPassSource.BARCODE,
                ),
        )

    @Test
    fun `start runs detection and preselects the suggestion`() =
        runTest {
            probeResult = barcodePass
            val vm = viewModel()

            vm.start(URI)

            val state = vm.uiState.value
            assertThat(state.active).isTrue()
            assertThat(state.detecting).isFalse()
            assertThat(state.suggested).isEqualTo(SharedDocType.BOARDING_PASS)
            assertThat(state.selected).isEqualTo(SharedDocType.BOARDING_PASS)
            assertThat(state.route).isNull()
            assertThat(probeCalls).isEqualTo(1)
        }

    @Test
    fun `garbage leaves nothing selected and confirm is a no-op`() =
        runTest {
            val vm = viewModel()
            vm.start(URI)

            vm.confirm()

            assertThat(vm.uiState.value.suggested).isNull()
            assertThat(vm.uiState.value.selected).isNull()
            assertThat(vm.uiState.value.route).isNull()
        }

    @Test
    fun `confirming train ticket routes to the trains form`() =
        runTest {
            val vm = viewModel()
            vm.start(URI)

            vm.select(SharedDocType.TRAIN_TICKET)
            vm.confirm()

            assertThat(vm.uiState.value.route).isEqualTo(IntakeRoute.TrainTicket(URI))
        }

    @Test
    fun `confirming the preselected boarding pass routes to the flights pass import`() =
        runTest {
            probeResult = barcodePass
            val vm = viewModel()
            vm.start(URI)

            vm.confirm()

            assertThat(vm.uiState.value.route).isEqualTo(IntakeRoute.FlightBoardingPass(URI))
        }

    @Test
    fun `user override beats the suggestion`() =
        runTest {
            probeResult = barcodePass
            val vm = viewModel()
            vm.start(URI)

            vm.select(SharedDocType.BOOKING_CONFIRMATION)
            vm.confirm()

            assertThat(vm.uiState.value.route).isEqualTo(IntakeRoute.FlightBookingConfirmation(URI))
        }

    @Test
    fun `reset clears the intake`() =
        runTest {
            val vm = viewModel()
            vm.start(URI)
            vm.select(SharedDocType.TRAIN_TICKET)

            vm.reset()

            assertThat(vm.uiState.value).isEqualTo(SharedFileIntakeUiState())
        }

    private companion object {
        const val URI = "content://com.example.files/ticket.pdf"
    }
}
