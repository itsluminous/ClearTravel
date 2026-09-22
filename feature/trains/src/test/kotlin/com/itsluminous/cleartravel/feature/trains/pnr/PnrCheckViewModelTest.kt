package com.itsluminous.cleartravel.feature.trains.pnr

import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.scrape.RuleRegistry
import com.itsluminous.cleartravel.core.scrape.RuleSource
import com.itsluminous.cleartravel.core.scrape.ScrapedData
import com.itsluminous.cleartravel.core.testing.Fixtures
import com.itsluminous.cleartravel.core.testing.MainDispatcherRule
import com.itsluminous.cleartravel.feature.trains.FakeTrainRepository
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.io.InputStream

/**
 * The PNR-check state machine over an INJECTED registry (ADR-014 hoist completed):
 * rule missing → RuleUnavailable, rule present → Running with the PNR expanded,
 * extracted → persisted via `applyStatusResult`, garbage → ParseFailed.
 */
class PnrCheckViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = FakeTrainRepository(now = { Fixtures.NOW })

    private class InlineRuleSource(
        private val files: Map<String, String>,
    ) : RuleSource {
        override fun ruleFileNames(): List<String> = files.keys.sorted()

        override fun openRule(fileName: String): InputStream = files.getValue(fileName).byteInputStream()
    }

    private val pnrRuleJson =
        """
        {
          "id": "${PnrCheckViewModel.RULE_ID}",
          "displayName": "Indian Railways PNR",
          "version": 1,
          "kind": "train",
          "urlTemplate": "https://www.indianrail.gov.in/enquiry/PNR/PnrEnquiry.html?pnr={pnr}",
          "readySignal": { "jsCondition": "true" },
          "extract": { "trainNumber": { "selector": "#x", "required": true } }
        }
        """.trimIndent()

    private fun registryWithRule(): RuleRegistry = RuleRegistry(InlineRuleSource(mapOf("indianrail-pnr.json" to pnrRuleJson)))

    private fun emptyRegistry(): RuleRegistry = RuleRegistry(InlineRuleSource(emptyMap()))

    @Test
    fun `start without the PNR rule stays RuleUnavailable`() {
        val vm = PnrCheckViewModel(emptyRegistry(), repository)

        vm.start("8524317690")

        assertThat(vm.uiState.value).isEqualTo(PnrCheckUiState.RuleUnavailable)
    }

    @Test
    fun `start builds a Running session with the PNR expanded and increments attempt on retry`() {
        val vm = PnrCheckViewModel(registryWithRule(), repository)

        vm.start("8524317690")
        val first = vm.uiState.value as PnrCheckUiState.Running
        assertThat(first.attempt).isEqualTo(1)
        assertThat(first.session.startUrl).endsWith("pnr=8524317690")

        vm.start("8524317690")
        val second = vm.uiState.value as PnrCheckUiState.Running
        assertThat(second.attempt).isEqualTo(2)
        assertThat(second.session).isNotSameInstanceAs(first.session)
    }

    @Test
    fun `onExtracted persists the mapped result and reports Applied with the train number`() =
        runTest {
            repository.seed(Fixtures.trainTicket(id = "ticket-1", pnr = "8524317690"))
            val vm = PnrCheckViewModel(registryWithRule(), repository)
            vm.start("8524317690")

            vm.onExtracted(
                ticketId = "ticket-1",
                pnr = "8524317690",
                data =
                    ScrapedData(
                        fields = mapOf("trainNumber" to "12951", "trainName" to "MUMBAI RAJDHANI"),
                        rows = listOf(mapOf("bookingStatus" to "CNF/B4/32/GN", "currentStatus" to "CNF/B4/32")),
                    ),
            )

            assertThat(vm.uiState.value).isEqualTo(PnrCheckUiState.Applied(trainNumber = "12951"))
            assertThat(repository.appliedResults.single().first).isEqualTo("ticket-1")
            assertThat(
                repository.appliedResults
                    .single()
                    .second.trainNumber,
            ).isEqualTo("12951")
        }

    @Test
    fun `onExtracted with unusable rows is a parse failure that keeps the session`() {
        val vm = PnrCheckViewModel(registryWithRule(), repository)
        vm.start("8524317690")
        val running = vm.uiState.value as PnrCheckUiState.Running

        vm.onExtracted(ticketId = "ticket-1", pnr = "8524317690", data = ScrapedData(fields = emptyMap(), rows = emptyList()))

        assertThat(vm.uiState.value).isEqualTo(PnrCheckUiState.ParseFailed(running.session, running.attempt))
        assertThat(repository.appliedResults).isEmpty()
    }
}
