package com.itsluminous.cleartravel.feature.trains.seatmap

import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.feature.trains.R
import org.junit.Test

/** Sleeper layouts talk about berths, chair-car / sitting layouts about seats (ADR-022 note). */
class SeatMapWordingTest {
    @Test
    fun `berth layouts keep the berth wording`() {
        assertThat(SeatMapWording.cellRes(SeatKind.BERTH, yours = false)).isEqualTo(R.string.trains_seatmap_cell)
        assertThat(SeatMapWording.cellRes(SeatKind.BERTH, yours = true)).isEqualTo(R.string.trains_seatmap_cell_yours)
        assertThat(SeatMapWording.chipNoCoachRes(SeatKind.BERTH)).isEqualTo(R.string.trains_seatmap_passenger_chip_no_coach)
        assertThat(SeatMapWording.chipNoneRes(SeatKind.BERTH)).isEqualTo(R.string.trains_seatmap_passenger_chip_none)
    }

    @Test
    fun `seat layouts say seat`() {
        assertThat(SeatMapWording.cellRes(SeatKind.SEAT, yours = false)).isEqualTo(R.string.trains_seatmap_seat)
        assertThat(SeatMapWording.cellRes(SeatKind.SEAT, yours = true)).isEqualTo(R.string.trains_seatmap_seat_yours)
        assertThat(SeatMapWording.chipNoCoachRes(SeatKind.SEAT)).isEqualTo(R.string.trains_seatmap_passenger_chip_no_coach_seat)
        assertThat(SeatMapWording.chipNoneRes(SeatKind.SEAT)).isEqualTo(R.string.trains_seatmap_passenger_chip_none_seat)
    }

    @Test
    fun `no resolved layout falls back to the berth wording`() {
        assertThat(SeatMapWording.chipNoCoachRes(null)).isEqualTo(R.string.trains_seatmap_passenger_chip_no_coach)
        assertThat(SeatMapWording.chipNoneRes(null)).isEqualTo(R.string.trains_seatmap_passenger_chip_none)
    }
}
