package com.itsluminous.cleartravel.core.scrape

import kotlinx.serialization.Serializable

/**
 * Versioned scrape-rule file schema stub (ADR-003 — behavior as data). One JSON file
 * per scraped site in `assets/scrape-rules/`; the Trains milestone fleshes out the
 * prefill/submit/readySignal/extract fields alongside the `RuleDrivenScraper` engine.
 * Placeholders in [urlTemplate]: `{pnr}`, `{flightNumber}`, `{date}`.
 */
@Serializable
data class ScrapeRule(
    val id: String,
    val displayName: String,
    val version: Int,
    val urlTemplate: String,
)
