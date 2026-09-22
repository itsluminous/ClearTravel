package com.itsluminous.cleartravel.ui

import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.data.crosstab.InMemoryJourneyAddRequestBus
import com.itsluminous.cleartravel.core.data.crosstab.JourneyAddResult
import com.itsluminous.cleartravel.core.model.JourneyType
import org.junit.Test

/**
 * ADR-029: the shell lands on Journeys in pick mode ONCE per request. The pending
 * request is a StateFlow, so an activity re-creation re-offers it — the latch must
 * swallow the repeat and only pass a genuinely new request.
 */
class JourneyPickCoordinatorTest {
    private val bus = InMemoryJourneyAddRequestBus()
    private val coordinator = JourneyPickCoordinator(bus)

    @Test
    fun `nothing pending lands nowhere`() {
        assertThat(coordinator.takeLanding(null)).isNull()
    }

    @Test
    fun `a request is offered for landing exactly once`() {
        val request = bus.request(JourneyType.TRAIN)

        assertThat(coordinator.takeLanding(request)).isEqualTo(request)
        assertThat(coordinator.takeLanding(request)).isNull()
        assertThat(coordinator.takeLanding(bus.pendingRequest.value)).isNull()
    }

    @Test
    fun `a newer request lands again`() {
        val first = bus.request(JourneyType.TRAIN)
        coordinator.takeLanding(first)

        val second = bus.request(JourneyType.FLIGHT)

        assertThat(coordinator.takeLanding(second)).isEqualTo(second)
    }

    @Test
    fun `completing a request clears the bus so nothing further is offered`() {
        val request = bus.request(JourneyType.TRAIN)
        coordinator.takeLanding(request)

        coordinator.complete(JourneyAddResult.Cancelled(request.nonce))

        assertThat(bus.pendingRequest.value).isNull()
        assertThat(coordinator.takeLanding(bus.pendingRequest.value)).isNull()
    }
}
