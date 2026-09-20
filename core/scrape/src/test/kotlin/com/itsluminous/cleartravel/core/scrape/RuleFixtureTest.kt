package com.itsluminous.cleartravel.core.scrape

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.serialization.Serializable
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * THE fixture harness (CI-critical, ADR-003/ADR-008): enumerates EVERY rule file in
 * `assets/scrape-rules/`, requires its recorded fixture pair, runs the pure
 * [RuleExtractor] against `page.html` and compares to `expected.json`. Adding a rule
 * file without a fixture fails this suite by construction.
 */
@RunWith(Parameterized::class)
class RuleFixtureTest(
    private val ruleFileName: String,
) {
    @Serializable
    data class Expected(
        val fields: Map<String, String> = emptyMap(),
        val rows: List<Map<String, String>> = emptyList(),
    )

    @Test
    fun `rule id matches its file name`() {
        val rule = RuleFixtureHarness.loadRule(ruleFileName)
        assertThat(rule.id).isEqualTo(ruleFileName.removeSuffix(".json"))
    }

    @Test
    fun `rule has a recorded fixture pair`() {
        val rule = RuleFixtureHarness.loadRule(ruleFileName)
        val errors = RuleFixtureHarness.missingFixtureErrors(rule.id)
        assertWithMessage(errors.joinToString("\n")).that(errors).isEmpty()
    }

    @Test
    fun `extractor output matches expected json for the recorded fixture`() {
        val rule = RuleFixtureHarness.loadRule(ruleFileName)
        val html =
            requireNotNull(RuleFixtureHarness.fixtureText(rule.id, "page.html")) {
                "Missing fixture page.html for ${rule.id}"
            }
        val expectedJson =
            requireNotNull(RuleFixtureHarness.fixtureText(rule.id, "expected.json")) {
                "Missing fixture expected.json for ${rule.id}"
            }
        val expected = RuleFixtureHarness.json.decodeFromString(Expected.serializer(), expectedJson)

        val result = RuleExtractor.extract(rule, html)

        assertWithMessage("Extraction failed: ${(result as? ExtractionResult.Failure)?.reason}")
            .that(result)
            .isInstanceOf(ExtractionResult.Success::class.java)
        val data = (result as ExtractionResult.Success).data
        assertThat(data.fields).containsExactlyEntriesIn(expected.fields)
        assertThat(data.rows).isEqualTo(expected.rows)
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun ruleFiles(): List<String> {
            val files = RuleFixtureHarness.ruleFileNames()
            check(files.isNotEmpty()) { "No rule files found in assets/scrape-rules" }
            return files
        }
    }
}
