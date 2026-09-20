package com.itsluminous.cleartravel.core.scrape

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Self-test of the fixture enforcement: proves the harness actually FAILS a rule that
 * ships without a fixture (the mechanism CI relies on), and passes rules that have one.
 */
class MissingFixtureEnforcementTest {
    @Test
    fun `a rule without a fixture is reported with actionable errors`() {
        val errors = RuleFixtureHarness.missingFixtureErrors("some-airline-without-fixture")

        assertThat(errors).hasSize(2)
        assertThat(errors[0]).contains("page.html")
        assertThat(errors[1]).contains("expected.json")
        assertThat(errors[0]).contains("fixtures/some-airline-without-fixture/")
    }

    @Test
    fun `the shipped indianrail rule has its complete fixture pair`() {
        assertThat(RuleFixtureHarness.missingFixtureErrors("indianrail-pnr")).isEmpty()
    }

    @Test
    fun `every asset rule file parses with the schema`() {
        val names = RuleFixtureHarness.ruleFileNames()
        assertThat(names).isNotEmpty()
        for (name in names) {
            val rule = RuleFixtureHarness.loadRule(name)
            assertThat(rule.version).isAtLeast(1)
            assertThat(rule.urlTemplate).startsWith("https://")
        }
    }
}

/** The spec's changed-markup fallback: a redesigned page yields ParseFailure, never a throw. */
class BrokenMarkupFallbackTest {
    @Test
    fun `changed markup produces ParseFailure carrying the raw html`() {
        val rule = RuleFixtureHarness.loadRule("indianrail-pnr.json")
        val brokenHtml =
            requireNotNull(RuleFixtureHarness.fixtureText("indianrail-pnr", "broken.html"))

        val result = RuleExtractor.extract(rule, brokenHtml)

        assertThat(result).isInstanceOf(ExtractionResult.Failure::class.java)
        val failure = result as ExtractionResult.Failure
        assertThat(failure.rawHtml).isEqualTo(brokenHtml)
        assertThat(failure.reason).contains("trainNumber")
    }

    @Test
    fun `garbage input never throws`() {
        val rule = RuleFixtureHarness.loadRule("indianrail-pnr.json")
        for (garbage in listOf("", "not html at all", "<<<%%%>>>", "<html><body>")) {
            val result = RuleExtractor.extract(rule, garbage)
            assertThat(result).isInstanceOf(ExtractionResult.Failure::class.java)
            assertThat((result as ExtractionResult.Failure).rawHtml).isEqualTo(garbage)
        }
    }
}
