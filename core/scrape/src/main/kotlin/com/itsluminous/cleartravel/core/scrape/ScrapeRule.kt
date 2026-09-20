package com.itsluminous.cleartravel.core.scrape

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Versioned scrape-rule file schema (ADR-003 / ADR-008 — behavior as data). One JSON
 * file per scraped site in `assets/scrape-rules/`; the generic engine executes any
 * rule inside a WebView and [RuleExtractor] parses the dumped HTML — engine code never
 * changes when a site changes, only the rule file (and its fixture) does.
 *
 * Placeholders usable in [urlTemplate] and [PrefillStep.valueTemplate]:
 * `{pnr}`, `{flightNumber}`, `{date}`, `{trainNumber}` — expanded from [ScrapeParams].
 *
 * Every rule file MUST ship with a fixture pair in
 * `core/scrape/src/test/resources/fixtures/<id>/{page.html,expected.json}` —
 * [the parameterized fixture test] fails CI for any rule without one.
 */
@Serializable
data class ScrapeRule(
    /** Stable identifier; also the fixture directory name (e.g. `indianrail-pnr`). */
    val id: String,
    val displayName: String,
    /** Bumped every time selectors change; helps debugging stale rules in the field. */
    val version: Int,
    /** `train` or `flight` — drives registry lookup. */
    val kind: RuleKind,
    /** IATA airline codes served by this rule (flight rules only, e.g. `["6E"]`). */
    val iataCodes: List<String> = emptyList(),
    val urlTemplate: String,
    /**
     * CSS selectors of known blocking overlays' dismiss controls (cookie-consent
     * accept buttons etc.). Any that exist AND are visible get clicked before
     * prefill and again on every ready-signal poll tick — consent SDKs often render
     * asynchronously after page load. A selector with no match is a silent no-op,
     * so a rule keeps working if the site stops showing the overlay. Recon
     * (docs/recon/NOTES.md): Air India's OneTrust dark filter intercepts ALL
     * pointer events until dismissed, stalling any hands-free flow.
     */
    val dismissSelectors: List<String> = emptyList(),
    /** Form fields to fill via JS injection once the page has loaded. */
    val prefill: List<PrefillStep> = emptyList(),
    /**
     * CSS selector of the submit control to auto-click. NULL means auto-submit is NOT
     * safe (e.g. captcha pages) — the engine emits [ScrapeEvent.NeedsUserAction] and
     * the user taps submit / solves the captcha themselves.
     */
    val submitSelector: String? = null,
    /** Marks the result as rendered; the engine polls this before dumping the DOM. */
    val readySignal: ReadySignal,
    /** Single-value fields: field name → extraction spec run against the dumped HTML. */
    val extract: Map<String, ExtractSpec> = emptyMap(),
    /** Repeating-row extraction (e.g. one row per passenger). */
    val rows: RowExtract? = null,
    /** Post-processing hints keyed by field name (dates/times normalization). */
    val postProcess: Map<String, PostProcessHint> = emptyMap(),
)

@Serializable
enum class RuleKind {
    @SerialName("train")
    TRAIN,

    @SerialName("flight")
    FLIGHT,
}

/** One prefill injection: `document.querySelector(selector).value = valueTemplate`. */
@Serializable
data class PrefillStep(
    val selector: String,
    /** May contain `{pnr}` / `{flightNumber}` / `{date}` / `{trainNumber}` placeholders. */
    val valueTemplate: String,
)

/**
 * Condition marking the result as rendered. Exactly one of [selector] (element must
 * exist AND be visible) or [jsCondition] (arbitrary JS expression evaluating truthy)
 * should be set; when both are set, [jsCondition] wins.
 */
@Serializable
data class ReadySignal(
    val selector: String? = null,
    val jsCondition: String? = null,
)

/**
 * How to pull one field out of the parsed document: a CSS [selector] (text content,
 * or [attribute] value when set), then an optional [regexChain] applied sequentially —
 * each pattern replaces the value with capture group 1 (or the whole match when the
 * pattern has no groups); a non-matching pattern yields an empty value.
 */
@Serializable
data class ExtractSpec(
    val selector: String? = null,
    val attribute: String? = null,
    val regexChain: List<String> = emptyList(),
    /** A blank required field makes the whole extraction a [ExtractionResult.Failure]. */
    val required: Boolean = false,
)

/** Repeating extraction: one result map per element matching [rowSelector]. */
@Serializable
data class RowExtract(
    val rowSelector: String,
    /** Specs are evaluated RELATIVE to each row element. */
    val fields: Map<String, ExtractSpec>,
    /** Fewer matched rows than this makes the extraction a failure. */
    val minRows: Int = 0,
)

/**
 * Best-effort normalization applied after extraction; an unparseable value keeps its
 * raw form (never a failure — the raw text is still useful to show).
 */
@Serializable
data class PostProcessHint(
    /** `date`, `time` or `trim`. */
    val type: String,
    /** java.time patterns tried in order (for `date`/`time`). */
    val inputFormats: List<String> = emptyList(),
    /** java.time output pattern; defaults to ISO (`yyyy-MM-dd` / `HH:mm`). */
    val outputFormat: String? = null,
)
