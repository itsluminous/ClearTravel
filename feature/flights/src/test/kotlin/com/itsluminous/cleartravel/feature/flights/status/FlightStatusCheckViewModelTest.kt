package com.itsluminous.cleartravel.feature.flights.status

import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.model.FlightStatus
import com.itsluminous.cleartravel.core.scrape.RuleRegistry
import com.itsluminous.cleartravel.core.scrape.RuleSource
import com.itsluminous.cleartravel.core.testing.Fixtures
import com.itsluminous.cleartravel.core.testing.MainDispatcherRule
import com.itsluminous.cleartravel.feature.flights.FakeCheckInRuleSource
import com.itsluminous.cleartravel.feature.flights.FakeFlightRepository
import com.itsluminous.cleartravel.feature.flights.FakeFlightStatusAlerts
import com.itsluminous.cleartravel.feature.flights.polling.FlightChange
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.InputStream
import java.time.Clock
import java.time.ZoneOffset

class FlightStatusCheckViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var repository: FakeFlightRepository
    private lateinit var alerts: FakeFlightStatusAlerts
    private lateinit var viewModel: FlightStatusCheckViewModel

    private val flight =
        Fixtures.flightJourney(
            airlineIata = "AI",
            flightNumber = "101",
            depGate = "",
            status = FlightStatus.SCHEDULED,
        )

    /** Minimal flight rule for AI, kept selector-consistent with [RESULT_HTML]. */
    private val testRuleJson =
        """
        {
          "id": "testair",
          "displayName": "Test Air",
          "version": 1,
          "kind": "flight",
          "iataCodes": ["AI"],
          "urlTemplate": "https://status.example/{flightNumber}?on={date}",
          "readySignal": { "selector": ".card" },
          "rows": {
            "rowSelector": ".card",
            "minRows": 1,
            "fields": {
              "status": { "selector": ".st" },
              "depTerminalGate": { "selector": ".tg" }
            }
          }
        }
        """.trimIndent()

    private class MapRuleSource(
        private val rules: Map<String, String>,
    ) : RuleSource {
        override fun ruleFileNames(): List<String> = rules.keys.sorted()

        override fun openRule(fileName: String): InputStream = rules.getValue(fileName).byteInputStream()
    }

    private fun buildViewModel(vararg ruleFiles: Pair<String, String>) {
        viewModel =
            FlightStatusCheckViewModel(
                repository = repository,
                ruleRegistry = RuleRegistry(MapRuleSource(mapOf(*ruleFiles))),
                checkInRuleSource = FakeCheckInRuleSource(),
                alerts = alerts,
                clock = Clock.fixed(Fixtures.NOW, ZoneOffset.UTC),
            )
    }

    @Before
    fun setUp() {
        repository = FakeFlightRepository()
        alerts = FakeFlightStatusAlerts()
        repository.seed(flight)
    }

    @Test
    fun `known airline enters the scraping state with an expanded url`() =
        runTest {
            buildViewModel("testair.json" to testRuleJson)

            viewModel.start(flight.id)

            val state = viewModel.uiState.value as StatusCheckUiState.Scraping
            assertThat(state.session.startUrl).isEqualTo("https://status.example/101?on=2026-09-20")
        }

    @Test
    fun `extraction applies the result, announces changes and finishes`() =
        runTest {
            buildViewModel("testair.json" to testRuleJson)
            viewModel.start(flight.id)
            val session = (viewModel.uiState.value as StatusCheckUiState.Scraping).session

            session.onHtmlDumped(RESULT_HTML)

            assertThat(viewModel.uiState.value).isInstanceOf(StatusCheckUiState.Done::class.java)
            val (id, result) = repository.appliedResults.single()
            assertThat(id).isEqualTo(flight.id)
            assertThat(result.status).isEqualTo(FlightStatus.DELAYED)
            assertThat(result.depGate).isEqualTo("9")
            val (announcedFlight, changes) = alerts.announced.single()
            assertThat(announcedFlight.id).isEqualTo(flight.id)
            assertThat(changes).contains(FlightChange.GateAssigned("9"))
            assertThat(repository.getFlight(flight.id)!!.lastFetchedAt).isEqualTo(Fixtures.NOW)
        }

    @Test
    fun `unknown airline falls back to a web search (SpiceJet path)`() =
        runTest {
            val spiceJet = Fixtures.flightJourney(airlineIata = "SG", flightNumber = "8194")
            repository.seed(spiceJet)
            buildViewModel("testair.json" to testRuleJson) // registry has no SG rule

            viewModel.start(spiceJet.id)

            val state = viewModel.uiState.value as StatusCheckUiState.WebSearchFallback
            assertThat(state.url).contains("8194")
            assertThat(state.url).startsWith("https://www.google.com/search")
            assertThat(repository.appliedResults).isEmpty()
        }

    @Test
    fun `changed markup lands in the parse-failed fallback`() =
        runTest {
            buildViewModel("testair.json" to testRuleJson)
            viewModel.start(flight.id)
            val session = (viewModel.uiState.value as StatusCheckUiState.Scraping).session

            session.onHtmlDumped("<html><body><p>site redesigned</p></body></html>")

            assertThat(viewModel.uiState.value).isInstanceOf(StatusCheckUiState.ParseFailed::class.java)
            assertThat(repository.appliedResults).isEmpty()
            assertThat(alerts.announced).isEmpty()
        }

    @Test
    fun `extracted-but-unmappable data is a parse failure, not a fabricated status`() =
        runTest {
            val vagueRule =
                testRuleJson
                    .replace("\"status\": { \"selector\": \".st\" }", "\"other\": { \"selector\": \".st\" }")
                    .replace("\"depTerminalGate\": { \"selector\": \".tg\" }", "\"misc\": { \"selector\": \".tg\" }")
            buildViewModel("testair.json" to vagueRule)
            viewModel.start(flight.id)
            val session = (viewModel.uiState.value as StatusCheckUiState.Scraping).session

            session.onHtmlDumped(RESULT_HTML)

            val state = viewModel.uiState.value
            assertThat(state).isInstanceOf(StatusCheckUiState.ParseFailed::class.java)
            assertThat(repository.appliedResults).isEmpty()
        }

    private companion object {
        const val RESULT_HTML =
            "<html><body><div class=\"card\">" +
                "<span class=\"st\">Delayed</span>" +
                "<p class=\"tg\">Terminal 2 | Gate 9</p>" +
                "</div></body></html>"
    }
}
