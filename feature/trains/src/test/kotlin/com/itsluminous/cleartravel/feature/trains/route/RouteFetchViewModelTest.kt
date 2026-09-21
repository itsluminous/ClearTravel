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

    /** In-test rule source carrying minimal route rules (same ids + templates). */
    private class InlineRuleSource(
        private val files: Map<String, String>,
    ) : RuleSource {
        override fun ruleFileNames(): List<String> = files.keys.sorted()

        override fun openRule(fileName: String): InputStream = files.getValue(fileName).byteInputStream()
    }

    private fun routeRuleJson(
        id: String,
        displayName: String,
        urlTemplate: String,
    ): String =
        """
        {
          "id": "$id",
          "displayName": "$displayName",
          "version": 1,
          "kind": "train",
          "urlTemplate": "$urlTemplate",
          "readySignal": { "jsCondition": "true" },
          "extract": { "trainNumber": { "selector": "#x", "required": true } }
        }
        """.trimIndent()

    private fun registryWithBothRules(): RuleRegistry =
        RuleRegistry(
            InlineRuleSource(
                mapOf(
                    "ixigo-route.json" to
                        routeRuleJson("ixigo-route", "ixigo Train Route", "https://www.ixigo.com/trains/{trainNumber}"),
                    "erail-route.json" to
                        routeRuleJson("erail-route", "eRail.in Train Route", "https://erail.in/train-enquiry/{trainNumber}"),
                ),
            ),
        )

    private fun registryWithIxigoOnly(): RuleRegistry =
        RuleRegistry(
            InlineRuleSource(
                mapOf(
                    "ixigo-route.json" to
                        routeRuleJson("ixigo-route", "ixigo Train Route", "https://www.ixigo.com/trains/{trainNumber}"),
                ),
            ),
        )

    private fun emptyRegistry(): RuleRegistry = RuleRegistry(InlineRuleSource(emptyMap()))

    private fun usableData(trainName: String? = null): ScrapedData =
        ScrapedData(
            fields =
                buildMap {
                    put("trainNumber", "22346")
                    if (trainName != null) put("trainName", trainName)
                },
            rows =
                listOf(
                    mapOf("stationName" to "Gomati Nagar", "arrival" to "starts", "departure" to "15:20", "day" to "1"),
                    mapOf("stationName" to "Patna Jn", "arrival" to "23:45", "departure" to "ends", "day" to "1"),
                ),
        )

    @Test
    fun `start without any route rule stays RuleUnavailable`() {
        val vm = RouteFetchViewModel(emptyRegistry(), repository)

        vm.start("22346")

        assertThat(vm.uiState.value).isEqualTo(RouteFetchUiState.RuleUnavailable)
    }

    @Test
    fun `start runs the ixigo PRIMARY source with the train number expanded`() {
        val vm = RouteFetchViewModel(registryWithBothRules(), repository)

        vm.start("22346")

        val running = vm.uiState.value as RouteFetchUiState.Running
        assertThat(running.session.startUrl).isEqualTo("https://www.ixigo.com/trains/22346")
        assertThat(running.sourceName).isEqualTo("ixigo Train Route")
        assertThat(running.hasAlternateSource).isTrue()
        assertThat(running.attempt).isEqualTo(1)
    }

    @Test
    fun `tryAlternateSource cycles to erail and back to ixigo`() {
        val vm = RouteFetchViewModel(registryWithBothRules(), repository)
        vm.start("22346")

        vm.tryAlternateSource()
        val erail = vm.uiState.value as RouteFetchUiState.Running
        assertThat(erail.session.startUrl).isEqualTo("https://erail.in/train-enquiry/22346")
        assertThat(erail.sourceName).isEqualTo("eRail.in Train Route")
        assertThat(erail.attempt).isEqualTo(2)

        vm.tryAlternateSource()
        val backToIxigo = vm.uiState.value as RouteFetchUiState.Running
        assertThat(backToIxigo.session.startUrl).isEqualTo("https://www.ixigo.com/trains/22346")
    }

    @Test
    fun `single-rule build reports no alternate source and cycling is a no-op`() {
        val vm = RouteFetchViewModel(registryWithIxigoOnly(), repository)
        vm.start("22346")

        val running = vm.uiState.value as RouteFetchUiState.Running
        assertThat(running.hasAlternateSource).isFalse()

        vm.tryAlternateSource()

        assertThat(vm.uiState.value).isSameInstanceAs(running)
    }

    @Test
    fun `retry re-runs the CURRENT source with a fresh session`() {
        val vm = RouteFetchViewModel(registryWithBothRules(), repository)
        vm.start("22346")
        vm.tryAlternateSource()
        val erail = vm.uiState.value as RouteFetchUiState.Running

        vm.onParseFailed()
        vm.retry()

        val retried = vm.uiState.value as RouteFetchUiState.Running
        assertThat(retried.session.startUrl).isEqualTo("https://erail.in/train-enquiry/22346")
        assertThat(retried.attempt).isEqualTo(3)
        assertThat(retried.session).isNotSameInstanceAs(erail.session)
    }

    @Test
    fun `extraction success replaces the stored route and applies with the station count`() =
        runTest {
            val ticket = Fixtures.trainTicket()
            repository.seed(ticket, stops = listOf(Fixtures.trainRouteStop(ticketId = ticket.id, stationName = "Old Stop")))
            val vm = RouteFetchViewModel(registryWithBothRules(), repository)
            vm.start("22346")

            vm.onExtracted(ticket.id, usableData())

            assertThat(vm.uiState.value).isEqualTo(RouteFetchUiState.Applied(stationCount = 2))
            val stored = repository.observeRouteStops(ticket.id).first()
            assertThat(stored.map { it.stationName }).containsExactly("Gomati Nagar", "Patna Jn").inOrder()
            assertThat(stored[0].departure).isEqualTo("15:20")
        }

    @Test
    fun `extraction backfills a blank ticket train name from the scraped field`() =
        runTest {
            val ticket = Fixtures.trainTicket(trainName = "")
            repository.seed(ticket)
            val vm = RouteFetchViewModel(registryWithBothRules(), repository)
            vm.start("22346")

            vm.onExtracted(ticket.id, usableData(trainName = "Vande Bharat Exp"))

            assertThat(repository.getTicket(ticket.id)?.trainName).isEqualTo("Vande Bharat Exp")
        }

    @Test
    fun `extraction never overwrites a user-entered train name`() =
        runTest {
            val ticket = Fixtures.trainTicket(trainName = "My Custom Name")
            repository.seed(ticket)
            val vm = RouteFetchViewModel(registryWithBothRules(), repository)
            vm.start("22346")

            vm.onExtracted(ticket.id, usableData(trainName = "Vande Bharat Exp"))

            assertThat(repository.getTicket(ticket.id)?.trainName).isEqualTo("My Custom Name")
        }

    @Test
    fun `blank scraped train name leaves the ticket untouched`() =
        runTest {
            val ticket = Fixtures.trainTicket(trainName = "")
            repository.seed(ticket)
            val vm = RouteFetchViewModel(registryWithBothRules(), repository)
            vm.start("22346")
            val before = repository.getTicket(ticket.id)

            vm.onExtracted(ticket.id, usableData(trainName = "  "))

            assertThat(repository.getTicket(ticket.id)).isEqualTo(before)
        }

    @Test
    fun `mapper-null data is treated like a parse failure keeping the page visible`() =
        runTest {
            val ticket = Fixtures.trainTicket()
            repository.seed(ticket)
            val vm = RouteFetchViewModel(registryWithBothRules(), repository)
            vm.start("22346")
            val running = vm.uiState.value as RouteFetchUiState.Running

            vm.onExtracted(ticket.id, ScrapedData(fields = emptyMap(), rows = listOf(mapOf("foo" to "bar"))))

            val failed = vm.uiState.value as RouteFetchUiState.ParseFailed
            assertThat(failed.session).isSameInstanceAs(running.session)
            assertThat(failed.attempt).isEqualTo(running.attempt)
            assertThat(failed.hasAlternateSource).isTrue()
            assertThat(repository.observeRouteStops(ticket.id).first()).isEmpty()
        }

    @Test
    fun `parse failure keeps the session and carries the source context`() =
        runTest {
            val vm = RouteFetchViewModel(registryWithBothRules(), repository)
            vm.start("22346")
            val first = vm.uiState.value as RouteFetchUiState.Running

            vm.onParseFailed()

            val failed = vm.uiState.value as RouteFetchUiState.ParseFailed
            assertThat(failed.session).isSameInstanceAs(first.session)
            assertThat(failed.sourceName).isEqualTo(first.sourceName)
        }

    @Test
    fun `applied state is observable through the ui state flow`() =
        runTest {
            val ticket = Fixtures.trainTicket()
            repository.seed(ticket)
            val vm = RouteFetchViewModel(registryWithBothRules(), repository)
            vm.start("22346")

            vm.uiState.test {
                assertThat(awaitItem()).isInstanceOf(RouteFetchUiState.Running::class.java)
                vm.onExtracted(ticket.id, usableData())
                assertThat(awaitItem()).isEqualTo(RouteFetchUiState.Applied(stationCount = 2))
            }
        }
}
