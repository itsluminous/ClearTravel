package com.itsluminous.cleartravel.core.scrape

import kotlinx.serialization.json.Json

/**
 * Loads every rule file from a [RuleSource] and selects rules for lookups:
 * trains by rule [ScrapeRule.id], flights by the flight number's IATA prefix
 * (`6E-2345` → the rule declaring `"6E"` in [ScrapeRule.iataCodes]). An unknown
 * airline returns null so features fall back to a plain web-search URL.
 *
 * A rule file that fails to parse is SKIPPED (never crashes the registry) — the
 * fixture test harness is what guarantees shipped rule files are valid.
 */
class RuleRegistry(
    private val source: RuleSource,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val rules: List<ScrapeRule> by lazy {
        source.ruleFileNames().mapNotNull { fileName ->
            runCatching {
                source.openRule(fileName).bufferedReader().use { reader ->
                    json.decodeFromString(ScrapeRule.serializer(), reader.readText())
                }
            }.getOrNull()
        }
    }

    fun all(): List<ScrapeRule> = rules

    fun ruleById(id: String): ScrapeRule? = rules.firstOrNull { it.id == id }

    /**
     * Selects the flight rule serving [flightNumber]'s airline. Accepted shapes:
     * `6E-2345`, `6E 2345`, `6E2345` — the IATA prefix is the leading 2 characters
     * (letters/digits), uppercased. Returns null for unknown airlines.
     */
    fun flightRuleFor(flightNumber: String): ScrapeRule? {
        val prefix = iataPrefixOf(flightNumber) ?: return null
        return rules.firstOrNull { rule ->
            rule.kind == RuleKind.FLIGHT && rule.iataCodes.any { it.equals(prefix, ignoreCase = true) }
        }
    }

    private fun iataPrefixOf(flightNumber: String): String? {
        val cleaned = flightNumber.trim()
        val separated = cleaned.split('-', ' ').firstOrNull()?.takeIf { it.length in 2..3 }
        val prefix = separated ?: cleaned.takeIf { it.length >= IATA_PREFIX_LENGTH }?.take(IATA_PREFIX_LENGTH)
        return prefix?.uppercase()?.takeIf { it.isNotBlank() }
    }

    private companion object {
        const val IATA_PREFIX_LENGTH = 2
    }
}
