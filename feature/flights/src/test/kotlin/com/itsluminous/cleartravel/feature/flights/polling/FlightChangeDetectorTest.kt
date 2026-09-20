package com.itsluminous.cleartravel.feature.flights.polling

import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.model.FlightStatus
import com.itsluminous.cleartravel.core.testing.Fixtures
import com.itsluminous.cleartravel.feature.flights.checkin.CheckInWindow
import org.junit.Test
import java.time.Instant

class FlightChangeDetectorTest {
    private val base = Fixtures.flightJourney(id = Fixtures.FIXED_ID)

    @Test
    fun `gate assigned when previous gate was blank`() {
        val changes = FlightChangeDetector.detect(base, base.copy(depGate = "24"))

        assertThat(changes).containsExactly(FlightChange.GateAssigned("24"))
    }

    @Test
    fun `gate changed when a different gate replaces the old one`() {
        val changes =
            FlightChangeDetector.detect(base.copy(depGate = "12"), base.copy(depGate = "24"))

        assertThat(changes).containsExactly(FlightChange.GateChanged(oldGate = "12", newGate = "24"))
    }

    @Test
    fun `no gate event when the gate is unchanged or cleared`() {
        assertThat(FlightChangeDetector.detect(base.copy(depGate = "12"), base.copy(depGate = "12"))).isEmpty()
        assertThat(FlightChangeDetector.detect(base.copy(depGate = "12"), base.copy(depGate = ""))).isEmpty()
    }

    @Test
    fun `delay on status transition to DELAYED`() {
        val estimate = base.schedDep!!.plusSeconds(3600)
        val changes =
            FlightChangeDetector.detect(
                base,
                base.copy(status = FlightStatus.DELAYED, estDep = estimate),
            )

        assertThat(changes).containsExactly(FlightChange.Delayed(estimate))
    }

    @Test
    fun `delay when the estimate slips past schedule even without a status change`() {
        val estimate = base.schedDep!!.plusSeconds(45 * 60)
        val changes = FlightChangeDetector.detect(base, base.copy(estDep = estimate))

        assertThat(changes).containsExactly(FlightChange.Delayed(estimate))
    }

    @Test
    fun `no delay for an already-delayed unchanged flight`() {
        val delayed = base.copy(status = FlightStatus.DELAYED, estDep = base.schedDep!!.plusSeconds(3600))

        assertThat(FlightChangeDetector.detect(delayed, delayed)).isEmpty()
    }

    @Test
    fun `cancellation fires once on transition`() {
        val cancelled = base.copy(status = FlightStatus.CANCELLED)

        assertThat(FlightChangeDetector.detect(base, cancelled)).containsExactly(FlightChange.Cancelled)
        assertThat(FlightChangeDetector.detect(cancelled, cancelled)).isEmpty()
    }

    @Test
    fun `belt assigned on landing`() {
        val changes =
            FlightChangeDetector.detect(
                base.copy(status = FlightStatus.DEPARTED),
                base.copy(status = FlightStatus.LANDED, baggageBelt = "7"),
            )

        assertThat(changes).containsExactly(FlightChange.BeltAssigned("7"))
    }

    @Test
    fun `multiple simultaneous changes are all reported`() {
        val changes =
            FlightChangeDetector.detect(
                base.copy(depGate = "12"),
                base.copy(depGate = "24", status = FlightStatus.CANCELLED),
            )

        assertThat(changes)
            .containsExactly(FlightChange.GateChanged("12", "24"), FlightChange.Cancelled)
    }

    // --- check-in window crossing ---

    private val window =
        CheckInWindow(
            opensAt = Instant.parse("2026-09-20T10:00:00Z"),
            closesAt = Instant.parse("2026-09-22T09:00:00Z"),
        )

    @Test
    fun `check-in opened when the window opened since the last evaluation`() {
        val opened =
            FlightChangeDetector.checkInOpened(
                window = window,
                lastCheckedAt = Instant.parse("2026-09-20T09:00:00Z"),
                now = Instant.parse("2026-09-20T11:00:00Z"),
            )

        assertThat(opened).isTrue()
    }

    @Test
    fun `no repeat once a previous evaluation already saw the open window`() {
        val opened =
            FlightChangeDetector.checkInOpened(
                window = window,
                lastCheckedAt = Instant.parse("2026-09-20T11:00:00Z"),
                now = Instant.parse("2026-09-20T12:00:00Z"),
            )

        assertThat(opened).isFalse()
    }

    @Test
    fun `first evaluation reports an already-open window`() {
        val opened =
            FlightChangeDetector.checkInOpened(
                window = window,
                lastCheckedAt = null,
                now = Instant.parse("2026-09-21T12:00:00Z"),
            )

        assertThat(opened).isTrue()
    }

    @Test
    fun `closed or missing window never reports`() {
        assertThat(
            FlightChangeDetector.checkInOpened(window, null, Instant.parse("2026-09-23T00:00:00Z")),
        ).isFalse()
        assertThat(FlightChangeDetector.checkInOpened(null, null, Instant.parse("2026-09-21T00:00:00Z"))).isFalse()
    }
}
