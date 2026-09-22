package com.itsluminous.cleartravel.core.data.crosstab

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.model.JourneyType
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** ADR-028 bus semantics: request → pending; complete → result + pending cleared; nonce matching. */
class InMemoryJourneyAddRequestBusTest {
    private val bus = InMemoryJourneyAddRequestBus()

    @Test
    fun `request publishes a pending request of the asked type`() {
        val request = bus.request(JourneyType.TRAIN)

        assertThat(bus.pendingRequest.value).isEqualTo(request)
        assertThat(request.type).isEqualTo(JourneyType.TRAIN)
    }

    @Test
    fun `complete with the matching nonce clears pending and delivers the result`() =
        runTest {
            bus.results.test {
                val request = bus.request(JourneyType.FLIGHT)

                bus.complete(JourneyAddResult.Added(request.nonce, JourneyType.FLIGHT, "flight-1"))

                assertThat(awaitItem()).isEqualTo(JourneyAddResult.Added(request.nonce, JourneyType.FLIGHT, "flight-1"))
                assertThat(bus.pendingRequest.value).isNull()
            }
        }

    @Test
    fun `cancelling a request delivers Cancelled and clears pending`() =
        runTest {
            bus.results.test {
                val request = bus.request(JourneyType.TRAIN)

                bus.complete(JourneyAddResult.Cancelled(request.nonce))

                assertThat(awaitItem()).isEqualTo(JourneyAddResult.Cancelled(request.nonce))
                assertThat(bus.pendingRequest.value).isNull()
            }
        }

    @Test
    fun `completing a stale nonce leaves the current request pending`() =
        runTest {
            bus.results.test {
                val current = bus.request(JourneyType.TRAIN)

                bus.complete(JourneyAddResult.Cancelled(nonce = current.nonce - 1))

                assertThat(awaitItem().nonce).isEqualTo(current.nonce - 1)
                assertThat(bus.pendingRequest.value).isEqualTo(current)
            }
        }

    @Test
    fun `a new request supersedes and cancels a still-pending one`() =
        runTest {
            bus.results.test {
                val first = bus.request(JourneyType.TRAIN)

                val second = bus.request(JourneyType.FLIGHT)

                assertThat(awaitItem()).isEqualTo(JourneyAddResult.Cancelled(first.nonce))
                assertThat(bus.pendingRequest.value).isEqualTo(second)
                assertThat(second.nonce).isNotEqualTo(first.nonce)
            }
        }
}
