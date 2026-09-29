package com.itsluminous.cleartravel.ui.intake

import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.data.intake.SharedTextKind
import org.junit.Test

/** ADR-042: the "What's this text?" state machine — preselect, override, confirm, reset. */
class SharedTextIntakeViewModelTest {
    private val viewModel = SharedTextIntakeViewModel()

    @Test
    fun `start preselects the classifier's suggestion`() {
        viewModel.start(FLIGHT_SMS)

        val state = viewModel.uiState.value
        assertThat(state.active).isTrue()
        assertThat(state.text).isEqualTo(FLIGHT_SMS)
        assertThat(state.suggested).isEqualTo(SharedTextKind.FLIGHT)
        assertThat(state.selected).isEqualTo(SharedTextKind.FLIGHT)
        assertThat(state.route).isNull()
    }

    @Test
    fun `train text is preselected as a train and confirms to the train route`() {
        viewModel.start(TRAIN_SMS)
        assertThat(viewModel.uiState.value.selected).isEqualTo(SharedTextKind.TRAIN)

        viewModel.confirm()

        assertThat(viewModel.uiState.value.route).isEqualTo(TextIntakeRoute.Train(TRAIN_SMS))
    }

    @Test
    fun `confirm follows the user's override, not the suggestion`() {
        viewModel.start(FLIGHT_SMS)
        viewModel.select(SharedTextKind.TRAIN)

        viewModel.confirm()

        val state = viewModel.uiState.value
        assertThat(state.suggested).isEqualTo(SharedTextKind.FLIGHT)
        assertThat(state.route).isEqualTo(TextIntakeRoute.Train(FLIGHT_SMS))
    }

    @Test
    fun `flight confirm routes to the flight form`() {
        viewModel.start(FLIGHT_SMS)

        viewModel.confirm()

        assertThat(viewModel.uiState.value.route).isEqualTo(TextIntakeRoute.Flight(FLIGHT_SMS))
    }

    @Test
    fun `reset clears everything and confirm is a no-op when inactive`() {
        viewModel.start(FLIGHT_SMS)
        viewModel.reset()

        assertThat(viewModel.uiState.value).isEqualTo(SharedTextIntakeUiState())
        viewModel.confirm()
        assertThat(viewModel.uiState.value.route).isNull()
    }

    private companion object {
        const val FLIGHT_SMS = "Akasa Air flight QP 1421 with PNR X4F18V from BLR (Terminal 1) to VNS on 29 May 26."
        const val TRAIN_SMS = "PNR:1234567890,TRN:12627,DOJ:20-10-26,SL,SBC-NDLS,Dep:20:00"
    }
}
