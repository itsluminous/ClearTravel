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
import java.io.File
import java.io.InputStream
import java.time.Clock
import java.time.LocalDate
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
            val outcome = requireNotNull(viewModel.lastOutcome.value)
            assertThat(outcome.kind).isEqualTo(CheckOutcomeKind.UPDATED)
            assertThat(outcome.flightId).isEqualTo(flight.id)
        }

    @Test
    fun `unknown airline without the google rule falls back to a plain web search`() =
        runTest {
            val spiceJet = Fixtures.flightJourney(airlineIata = "SG", flightNumber = "8194")
            repository.seed(spiceJet)
            buildViewModel("testair.json" to testRuleJson) // registry has neither SG nor google-flights

            viewModel.start(spiceJet.id)

            val state = viewModel.uiState.value as StatusCheckUiState.WebSearchFallback
            assertThat(state.url).contains("8194")
            assertThat(state.url).startsWith("https://www.google.com/search")
            assertThat(repository.appliedResults).isEmpty()
        }

    // ---- ADR-026: airline-agnostic Google flight-status panel fallback ----

    @Test
    fun `unknown airline runs the google panel rule with the airline code, bare flight number and the journey date`() =
        runTest {
            val indigo = Fixtures.flightJourney(airlineIata = "6e", flightNumber = "02001", date = CAPTURE_DAY)
            repository.seed(indigo)
            buildViewModel("testair.json" to testRuleJson, "google-flights.json" to googleRuleJson())

            viewModel.start(indigo.id)

            val state = viewModel.uiState.value as StatusCheckUiState.Scraping
            assertThat(state.viaWebSearch).isTrue()
            assertThat(state.session.rule.id).isEqualTo(GoogleFlightsExtractor.RULE_ID)
            assertThat(state.session.startUrl).isEqualTo("https://www.google.com/search?q=6E+2001+flight+status+22+September+2026&hl=en")
        }

    @Test
    fun `undated flight never scrapes google - plain web search instead`() =
        runTest {
            val undated = Fixtures.flightJourney(airlineIata = "6E", flightNumber = "2001", date = null)
            repository.seed(undated)
            buildViewModel("google-flights.json" to googleRuleJson())

            viewModel.start(undated.id)

            assertThat(viewModel.uiState.value).isInstanceOf(StatusCheckUiState.WebSearchFallback::class.java)
        }

    @Test
    fun `google card dump applies the parsed status, announces changes and finishes`() =
        runTest {
            val indigo =
                Fixtures.flightJourney(
                    airlineIata = "6E",
                    flightNumber = "2001",
                    date = CAPTURE_DAY,
                    depAirport = "PAT",
                    arrAirport = "DEL",
                    depTerminal = "",
                    arrTerminal = "",
                    status = FlightStatus.SCHEDULED,
                )
            repository.seed(indigo)
            buildViewModel("google-flights.json" to googleRuleJson())
            viewModel.start(indigo.id)
            val session = (viewModel.uiState.value as StatusCheckUiState.Scraping).session

            session.onHtmlDumped(googleFixture("departed-6e2001.html"))

            assertThat(viewModel.uiState.value).isInstanceOf(StatusCheckUiState.Done::class.java)
            val (id, result) = repository.appliedResults.single()
            assertThat(id).isEqualTo(indigo.id)
            assertThat(result.status).isEqualTo(FlightStatus.DEPARTED)
            assertThat(result.arrTerminal).isEqualTo("2")
            assertThat(result.estDep).isNotNull()
            assertThat(repository.getFlight(indigo.id)!!.lastFetchedAt).isEqualTo(Fixtures.NOW)
            assertThat(viewModel.lastOutcome.value!!.kind).isEqualTo(CheckOutcomeKind.UPDATED)
        }

    @Test
    fun `google page without a flight card is a parse failure that keeps the page and offers no second fallback`() =
        runTest {
            val indigo = Fixtures.flightJourney(airlineIata = "6E", flightNumber = "2001", date = CAPTURE_DAY)
            repository.seed(indigo)
            buildViewModel("google-flights.json" to googleRuleJson())
            viewModel.start(indigo.id)
            val session = (viewModel.uiState.value as StatusCheckUiState.Scraping).session

            session.onHtmlDumped(googleFixture("no-panel.html"))

            val state = viewModel.uiState.value as StatusCheckUiState.ParseFailed
            assertThat(state.session).isSameInstanceAs(session)
            assertThat(state.viaWebSearch).isTrue()
            assertThat(state.canTryWebSearch).isFalse()
            assertThat(repository.appliedResults).isEmpty()
            assertThat(viewModel.lastOutcome.value!!.kind).isEqualTo(CheckOutcomeKind.FAILED)
        }

    @Test
    fun `google card for another day is refused - nothing written`() =
        runTest {
            val indigo = Fixtures.flightJourney(airlineIata = "6E", flightNumber = "2001", date = CAPTURE_DAY.plusDays(2))
            repository.seed(indigo)
            buildViewModel("google-flights.json" to googleRuleJson())
            viewModel.start(indigo.id)
            val session = (viewModel.uiState.value as StatusCheckUiState.Scraping).session

            session.onHtmlDumped(googleFixture("departed-6e2001.html"))

            assertThat(viewModel.uiState.value).isInstanceOf(StatusCheckUiState.ParseFailed::class.java)
            assertThat(repository.appliedResults).isEmpty()
        }

    @Test
    fun `needs-user-action from the google rule is ignored - direct GET has nothing to submit`() =
        runTest {
            val indigo = Fixtures.flightJourney(airlineIata = "6E", flightNumber = "2001", date = CAPTURE_DAY)
            repository.seed(indigo)
            buildViewModel("google-flights.json" to googleRuleJson())
            viewModel.start(indigo.id)
            val session = (viewModel.uiState.value as StatusCheckUiState.Scraping).session

            session.onPageReady()

            val state = viewModel.uiState.value as StatusCheckUiState.Scraping
            assertThat(state.waitingForUser).isFalse()
        }

    @Test
    fun `airline rule failure offers the web search and retryViaWebSearch switches to the google rule`() =
        runTest {
            val dated = flight.copy(date = CAPTURE_DAY)
            repository.seed(dated)
            buildViewModel("testair.json" to testRuleJson, "google-flights.json" to googleRuleJson())
            viewModel.start(dated.id)
            val airlineSession = (viewModel.uiState.value as StatusCheckUiState.Scraping).session
            assertThat(airlineSession.rule.id).isEqualTo("testair")

            airlineSession.onHtmlDumped("<html><body><p>site redesigned</p></body></html>")
            val failed = viewModel.uiState.value as StatusCheckUiState.ParseFailed
            assertThat(failed.canTryWebSearch).isTrue()
            assertThat(failed.viaWebSearch).isFalse()

            viewModel.retryViaWebSearch()

            val google = viewModel.uiState.value as StatusCheckUiState.Scraping
            assertThat(google.attempt).isEqualTo(2)
            assertThat(google.viaWebSearch).isTrue()
            assertThat(google.session.startUrl).contains("q=AI+101+flight+status+22+September+2026")

            // A later plain retry stays on Google for this check.
            google.session.onHtmlDumped(googleFixture("no-panel.html"))
            viewModel.retry()
            assertThat((viewModel.uiState.value as StatusCheckUiState.Scraping).session.rule.id)
                .isEqualTo(GoogleFlightsExtractor.RULE_ID)
        }

    @Test
    fun `airline rule failure without the google rule offers no web search action`() =
        runTest {
            buildViewModel("testair.json" to testRuleJson)
            viewModel.start(flight.id)
            (viewModel.uiState.value as StatusCheckUiState.Scraping).session.onHtmlDumped("<html></html>")

            assertThat((viewModel.uiState.value as StatusCheckUiState.ParseFailed).canTryWebSearch).isFalse()
            viewModel.retryViaWebSearch() // no-op

            assertThat(viewModel.uiState.value).isInstanceOf(StatusCheckUiState.ParseFailed::class.java)
        }

    /** The SHIPPED google-flights rule file (core:scrape asset), so this test breaks if it drifts. */
    private fun googleRuleJson(): String =
        listOf(
            File("../../core/scrape/src/main/assets/scrape-rules/google-flights.json"),
            File("core/scrape/src/main/assets/scrape-rules/google-flights.json"),
        ).firstOrNull(File::isFile)?.readText()
            ?: error("google-flights.json asset not found from ${File(".").absolutePath}")

    private fun googleFixture(name: String): String =
        requireNotNull(javaClass.classLoader?.getResourceAsStream("google-flights/$name")) { "missing fixture $name" }
            .use { it.readBytes().toString(Charsets.UTF_8) }

    @Test
    fun `changed markup lands in the parse-failed fallback keeping the session and a FAILED outcome`() =
        runTest {
            buildViewModel("testair.json" to testRuleJson)
            viewModel.start(flight.id)
            val session = (viewModel.uiState.value as StatusCheckUiState.Scraping).session

            session.onHtmlDumped("<html><body><p>site redesigned</p></body></html>")

            val state = viewModel.uiState.value as StatusCheckUiState.ParseFailed
            // The raw page stays visible: same session + attempt = same WebView (D2).
            assertThat(state.session).isSameInstanceAs(session)
            assertThat(state.attempt).isEqualTo(1)
            assertThat(repository.appliedResults).isEmpty()
            assertThat(alerts.announced).isEmpty()
            val outcome = requireNotNull(viewModel.lastOutcome.value)
            assertThat(outcome.kind).isEqualTo(CheckOutcomeKind.FAILED)
            assertThat(outcome.flightId).isEqualTo(flight.id)
            assertThat(outcome.at).isEqualTo(Fixtures.NOW)
        }

    @Test
    fun `retry after a parse failure starts a fresh attempt with a new session`() =
        runTest {
            buildViewModel("testair.json" to testRuleJson)
            viewModel.start(flight.id)
            val firstSession = (viewModel.uiState.value as StatusCheckUiState.Scraping).session
            firstSession.onHtmlDumped("<html><body></body></html>")
            assertThat(viewModel.uiState.value).isInstanceOf(StatusCheckUiState.ParseFailed::class.java)

            viewModel.retry()

            val state = viewModel.uiState.value as StatusCheckUiState.Scraping
            assertThat(state.attempt).isEqualTo(2)
            assertThat(state.session).isNotSameInstanceAs(firstSession)
            assertThat(state.session.startUrl).isEqualTo(firstSession.startUrl)
        }

    @Test
    fun `retry then successful extraction completes with an UPDATED outcome`() =
        runTest {
            buildViewModel("testair.json" to testRuleJson)
            viewModel.start(flight.id)
            (viewModel.uiState.value as StatusCheckUiState.Scraping)
                .session
                .onHtmlDumped("<html><body></body></html>")
            viewModel.retry()
            val retrySession = (viewModel.uiState.value as StatusCheckUiState.Scraping).session

            retrySession.onHtmlDumped(RESULT_HTML)

            assertThat(viewModel.uiState.value).isInstanceOf(StatusCheckUiState.Done::class.java)
            assertThat(repository.appliedResults).hasSize(1)
            val outcome = requireNotNull(viewModel.lastOutcome.value)
            assertThat(outcome.kind).isEqualTo(CheckOutcomeKind.UPDATED)
        }

    @Test
    fun `extraction with no user-visible changes records a NO_CHANGES outcome`() =
        runTest {
            val unchanged =
                Fixtures.flightJourney(
                    airlineIata = "AI",
                    flightNumber = "101",
                    status = FlightStatus.DELAYED,
                    depGate = "9",
                    depTerminal = "2",
                )
            repository.seed(unchanged)
            buildViewModel("testair.json" to testRuleJson)
            viewModel.start(unchanged.id)
            val session = (viewModel.uiState.value as StatusCheckUiState.Scraping).session

            session.onHtmlDumped(RESULT_HTML)

            val state = viewModel.uiState.value as StatusCheckUiState.Done
            assertThat(state.changes).isEmpty()
            assertThat(alerts.announced).isEmpty()
            val outcome = requireNotNull(viewModel.lastOutcome.value)
            assertThat(outcome.kind).isEqualTo(CheckOutcomeKind.NO_CHANGES)
            assertThat(outcome.flightId).isEqualTo(unchanged.id)
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
            assertThat((state as StatusCheckUiState.ParseFailed).session).isSameInstanceAs(session)
            assertThat(repository.appliedResults).isEmpty()
            assertThat(viewModel.lastOutcome.value!!.kind).isEqualTo(CheckOutcomeKind.FAILED)
        }

    private companion object {
        /** Day the Google fixtures were captured (their selected date tab). */
        val CAPTURE_DAY: LocalDate = LocalDate.of(2026, 9, 22)
        const val RESULT_HTML =
            "<html><body><div class=\"card\">" +
                "<span class=\"st\">Delayed</span>" +
                "<p class=\"tg\">Terminal 2 | Gate 9</p>" +
                "</div></body></html>"
    }
}
