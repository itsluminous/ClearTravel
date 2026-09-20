package com.itsluminous.cleartravel.feature.trains.route

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.scrape.RuleRegistry
import com.itsluminous.cleartravel.core.scrape.RuleSource
import com.itsluminous.cleartravel.core.scrape.ScrapedData
import com.itsluminous.cleartravel.core.testing.Fixtures
import com.itsluminous.cleartravel.core.testing.MainDispatcherRule
import com.itsluminous.cleartravel.feature.trains.FakeTrainRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.io.InputStream

class RouteFetchViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = FakeTrainRepository(now = { Fixtures.NOW })

    /** In-test rule source carrying a minimal erail-route rule (same id + template). */
    private class InlineRuleSource(
        private val files: Map<String, String>,
    ) : RuleSource {
        override fun ruleFileNames(): List<String> = files.keys.sorted()

        override fun openRule(fileName: String): InputStream = files.getValue(fileName).byteInputStream()
    }

    private fun registryWithRouteRule(): RuleRegistry =
        RuleRegistry(
            InlineRuleSource(
                mapOf(
                    "erail-route.json" to
                        """
                        {
                          "id": "erail-route",
                          "displayName": "eRail.in Train Route",
                          "version": 1,
                          "kind": "train",
                          "urlTemplate": "https://erail.in/train-enquiry/{trainNumber}",
                          "readySignal": { "jsCondition": "true" },
                          "extract": { "trainNumber": { "selector": "#x", "required": true } }
                        }
                        """.trimIndent(),
                ),
            ),
        )

    private fun emptyRegistry(): RuleRegistry = RuleRegistry(InlineRuleSource(emptyMap()))

    private fun usableData(): ScrapedData =
        ScrapedData(
            fields = mapOf("trainNumber" to "22346"),
            rows =
                listOf(
                    mapOf("stationName" to "Gomtinagar (Lucknow)", "arrival" to "First", "departure" to "15.20", "day" to "1"),
                    mapOf("stationName" to "Patna Jn", "arrival" to "23.45", "departure" to "Last", "day" to "1"),
                ),
        )

    @Test
    fun `start without the rule stays RuleUnavailable`() {
        val vm = RouteFetchViewModel(emptyRegistry(), repository)

        vm.start("22346")

        assertThat(vm.uiState.value).isEqualTo(RouteFetchUiState.RuleUnavailable)
    }

    @Test
    fun `start builds a running session with the train number expanded`() {
        val vm = RouteFetchViewModel(registryWithRouteRule(), repository)

        vm.start("22346")

        val running = vm.uiState.value as RouteFetchUiState.Running
        assertThat(running.session.startUrl).isEqualTo("https://erail.in/train-enquiry/22346")
        assertThat(running.attempt).isEqualTo(1)
    }

    @Test
    fun `extraction success replaces the stored route and applies with the station count`() =
        runTest {
            val ticket = Fixtures.trainTicket()
            repository.seed(ticket, stops = listOf(Fixtures.trainRouteStop(ticketId = ticket.id, stationName = "Old Stop")))
            val vm = RouteFetchViewModel(registryWithRouteRule(), repository)
            vm.start("22346")

            vm.onExtracted(ticket.id, usableData())

            assertThat(vm.uiState.value).isEqualTo(RouteFetchUiState.Applied(stationCount = 2))
            val stored = repository.observeRouteStops(ticket.id).first()
            assertThat(stored.map { it.stationName }).containsExactly("Gomtinagar (Lucknow)", "Patna Jn").inOrder()
            assertThat(stored[0].departure).isEqualTo("15:20")
        }

    @Test
    fun `mapper-null data is treated like a parse failure keeping the page visible`() =
        runTest {
            val ticket = Fixtures.trainTicket()
            repository.seed(ticket)
            val vm = RouteFetchViewModel(registryWithRouteRule(), repository)
            vm.start("22346")
            val running = vm.uiState.value as RouteFetchUiState.Running

            vm.onExtracted(ticket.id, ScrapedData(fields = emptyMap(), rows = listOf(mapOf("foo" to "bar"))))

            val failed = vm.uiState.value as RouteFetchUiState.ParseFailed
            assertThat(failed.session).isSameInstanceAs(running.session)
            assertThat(failed.attempt).isEqualTo(running.attempt)
            assertThat(repository.observeRouteStops(ticket.id).first()).isEmpty()
        }

    @Test
    fun `parse failure keeps the session and retry rebuilds a fresh attempt`() =
        runTest {
            val vm = RouteFetchViewModel(registryWithRouteRule(), repository)
            vm.start("22346")
            val first = vm.uiState.value as RouteFetchUiState.Running

            vm.onParseFailed()
            val failed = vm.uiState.value as RouteFetchUiState.ParseFailed
            assertThat(failed.session).isSameInstanceAs(first.session)

            vm.start("22346")
            val retried = vm.uiState.value as RouteFetchUiState.Running
            assertThat(retried.attempt).isEqualTo(2)
            assertThat(retried.session).isNotSameInstanceAs(first.session)
        }

    @Test
    fun `applied state is observable through the ui state flow`() =
        runTest {
            val ticket = Fixtures.trainTicket()
            repository.seed(ticket)
            val vm = RouteFetchViewModel(registryWithRouteRule(), repository)
            vm.start("22346")

            vm.uiState.test {
                assertThat(awaitItem()).isInstanceOf(RouteFetchUiState.Running::class.java)
                vm.onExtracted(ticket.id, usableData())
                assertThat(awaitItem()).isEqualTo(RouteFetchUiState.Applied(stationCount = 2))
            }
        }
}
