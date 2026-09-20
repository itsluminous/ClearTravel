package com.itsluminous.cleartravel.core.scrape

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.InputStream

class RuleRegistryTest {
    private class FakeSource(
        private val files: Map<String, String>,
    ) : RuleSource {
        override fun ruleFileNames(): List<String> = files.keys.sorted()

        override fun openRule(fileName: String): InputStream = files.getValue(fileName).byteInputStream()
    }

    private fun ruleJson(
        id: String,
        kind: String,
        iata: List<String> = emptyList(),
    ): String {
        val codes = iata.joinToString(",") { "\"$it\"" }
        return """
            {
              "id": "$id",
              "displayName": "$id",
              "version": 1,
              "kind": "$kind",
              "iataCodes": [$codes],
              "urlTemplate": "https://example.com/$id",
              "readySignal": { "selector": "#result" }
            }
            """.trimIndent()
    }

    private val registry =
        RuleRegistry(
            FakeSource(
                mapOf(
                    "indianrail-pnr.json" to ruleJson("indianrail-pnr", "train"),
                    "indigo.json" to ruleJson("indigo", "flight", listOf("6E")),
                    "airindia.json" to ruleJson("airindia", "flight", listOf("AI", "IX")),
                ),
            ),
        )

    @Test
    fun `flight rule selected by iata prefix with dash`() {
        assertThat(registry.flightRuleFor("6E-2345")?.id).isEqualTo("indigo")
    }

    @Test
    fun `flight rule selected without separator and case-insensitively`() {
        assertThat(registry.flightRuleFor("6e2345")?.id).isEqualTo("indigo")
        assertThat(registry.flightRuleFor("ai 302")?.id).isEqualTo("airindia")
    }

    @Test
    fun `rule serving multiple iata codes matches each`() {
        assertThat(registry.flightRuleFor("IX-1140")?.id).isEqualTo("airindia")
    }

    @Test
    fun `unknown airline returns null so features fall back to web search`() {
        assertThat(registry.flightRuleFor("XY-99")).isNull()
        assertThat(registry.flightRuleFor("")).isNull()
    }

    @Test
    fun `train rules never match flight lookups`() {
        // "IN" does not collide, but even a crafted prefix must not select a train rule.
        assertThat(registry.flightRuleFor("IN-1234")).isNull()
    }

    @Test
    fun `ruleById finds trains and unknown id returns null`() {
        assertThat(registry.ruleById("indianrail-pnr")?.kind).isEqualTo(RuleKind.TRAIN)
        assertThat(registry.ruleById("nope")).isNull()
    }

    @Test
    fun `malformed rule file is skipped without crashing the registry`() {
        val withBroken =
            RuleRegistry(
                FakeSource(
                    mapOf(
                        "good.json" to ruleJson("good", "flight", listOf("QP")),
                        "broken.json" to "{ not json at all",
                    ),
                ),
            )

        assertThat(withBroken.all().map(ScrapeRule::id)).containsExactly("good")
        assertThat(withBroken.flightRuleFor("QP-1103")?.id).isEqualTo("good")
    }

    @Test
    fun `registry loads the real shipped asset rules`() {
        val real = RuleRegistry(RuleFixtureHarness.FileRuleSource())

        assertThat(real.ruleById("indianrail-pnr")).isNotNull()
        assertThat(real.ruleById("indianrail-pnr")?.submitSelector).isNull()
    }
}
