package com.itsluminous.cleartravel.core.scrape

/**
 * Structured output of a successful extraction: single-value [fields] plus repeating
 * [rows] (e.g. one map per passenger). All values are whitespace-normalized text.
 */
data class ScrapedData(
    val fields: Map<String, String>,
    val rows: List<Map<String, String>> = emptyList(),
    /** Named secondary row-sets declared by `ScrapeRule.extraRows` (ADR-022). */
    val extraRows: Map<String, List<Map<String, String>>> = emptyMap(),
)

/**
 * Outcome of running [RuleExtractor] on dumped HTML. NEVER an exception: unexpected
 * markup produces [Failure] carrying the raw HTML so the caller can fall back to
 * showing the raw page (spec requirement).
 */
sealed interface ExtractionResult {
    data class Success(
        val data: ScrapedData,
    ) : ExtractionResult

    data class Failure(
        val reason: String,
        val rawHtml: String,
    ) : ExtractionResult
}
