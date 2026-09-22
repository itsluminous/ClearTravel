package com.itsluminous.cleartravel.core.scrape

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The airline-agnostic Google flight-status fallback rule (ADR-026): class-free
 * anchors, a heading sentinel that fails cleanly when Google shows no rich card, id-only
 * lookup (never IATA dispatch) and the raw dump travelling with `Extracted` so the
 * feature-side card parser can run on it.
 */
class GoogleFlightsRuleTest {
    private val rule = RuleFixtureHarness.loadRule("google-flights.json")

    @Test
    fun `no rich card on the page is a clean failure naming the heading sentinel`() {
        val html = requireNotNull(RuleFixtureHarness.fixtureText("google-flights", "no-panel.html"))

        val result = RuleExtractor.extract(rule, html)

        assertThat(result).isInstanceOf(ExtractionResult.Failure::class.java)
        assertThat((result as ExtractionResult.Failure).reason).contains("heading")
        assertThat(result.rawHtml).isEqualTo(html)
    }

    @Test
    fun `rule uses no obfuscated google class names`() {
        val selectors =
            rule.extract.values.mapNotNull { it.selector } + rule.dismissSelectors +
                listOfNotNull(rule.readySignal.jsCondition)
        for (selector in selectors) {
            // Every anchor is a tag, ARIA role/attribute, id or text match — never a `.class`.
            assertThat(selector).doesNotContainMatch("\\.[A-Za-z][A-Za-z0-9]{4,6}\\b")
        }
    }

    @Test
    fun `rule is never selected by iata dispatch and is found by id`() {
        val registry = RuleRegistry(RuleFixtureHarness.FileRuleSource())

        assertThat(rule.iataCodes).isEmpty()
        assertThat(registry.flightRuleFor("6E-2001")).isNull()
        assertThat(registry.flightRuleFor("EK-500")).isNull()
        assertThat(registry.ruleById("google-flights")?.kind).isEqualTo(RuleKind.FLIGHT)
    }

    @Test
    fun `start url expands the airline code, flight number and spelled-out date into the query`() {
        // The date words are what make Google select the journey's day tab (v2, live validation:
        // a bare "SG 128 flight status" pre-selected the next operating day).
        val session = RuleDrivenScrapeSession(rule, ScrapeParams(airlineIata = "6E", flightNumber = "2001", date = "22+September+2026"))

        assertThat(session.startUrl)
            .isEqualTo("https://www.google.com/search?q=6E+2001+flight+status+22+September+2026&hl=en")
        assertThat(session.dismissJavaScript()).contains("#L2AGLb")
        assertThat(session.submitJavaScript()).isNull()
        assertThat(session.readySignalJavaScript()).contains("data-bkt")
    }

    @Test
    fun `successful dump emits Extracted carrying the same raw html`() =
        runTest {
            val html = requireNotNull(RuleFixtureHarness.fixtureText("google-flights", "page.html"))
            val session = RuleDrivenScrapeSession(rule, ScrapeParams(airlineIata = "AI", flightNumber = "101"))

            session.events.test {
                session.onHtmlDumped(html)
                val event = awaitItem() as ScrapeEvent.Extracted
                assertThat(event.rawHtml).isEqualTo(html)
                assertThat(event.data.fields["heading"]).isEqualTo("Flight status")
                assertThat(event.data.fields["headerStatus"]).isEqualTo("Scheduled")
            }
        }

    @Test
    fun `live device dump (empty tabpanels, padded h3, no maindata blob) still satisfies the rule`() {
        val html = requireNotNull(RuleFixtureHarness.fixtureText("google-flights", "live-landed-6e2001.html"))

        val result = RuleExtractor.extract(rule, html)

        assertThat(result).isInstanceOf(ExtractionResult.Success::class.java)
        val fields = (result as ExtractionResult.Success).data.fields
        assertThat(fields["heading"]).isEqualTo("Flight status")
        assertThat(fields["flightLabel"]).isEqualTo("IndiGo 6E 2001")
        assertThat(fields["selectedDate"]).isEqualTo("Tue, 22 Sept")
        assertThat(fields["headerStatus"]).isEqualTo("Arrived")
        assertThat(fields["source"]).isEqualTo("Cirium")
        assertThat(fields["mainStatuses"]).isEmpty() // blob absent on the live page — optional by design
    }

    @Test
    fun `live cancelled dump (SG 128, duplicate card in a hidden dialog) satisfies the rule with a Cancelled header`() {
        val html = requireNotNull(RuleFixtureHarness.fixtureText("google-flights", "live-cancelled-sg128.html"))

        val result = RuleExtractor.extract(rule, html)

        assertThat(result).isInstanceOf(ExtractionResult.Success::class.java)
        val fields = (result as ExtractionResult.Success).data.fields
        assertThat(fields["flightLabel"]).isEqualTo("SpiceJet SG 128")
        assertThat(fields["selectedDate"]).isEqualTo("Tue, 22 Sept")
        assertThat(fields["headerStatus"]).isEqualTo("Cancelled")
        assertThat(fields["source"]).isEqualTo("OAG")
        // Google's own per-day enum list — diagnostics only; the rendered card decides.
        assertThat(fields["mainStatuses"]).isEqualTo("ARRIVED_DELAYED, CANCELED, SCHEDULED_STATUS, SCHEDULED_STATUS")
    }

    @Test
    fun `airlineIata placeholder expands and defaults to empty`() {
        assertThat(ScrapeParams(airlineIata = "EK").expand("q={airlineIata}+{flightNumber}")).isEqualTo("q=EK+")
        assertThat(ScrapeParams().expand("{airlineIata}")).isEmpty()
    }
}
