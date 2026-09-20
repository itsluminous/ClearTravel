package com.itsluminous.cleartravel.core.scrape

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * PURE HTML → structured-data extraction (no Android, no WebView): the exact code
 * path exercised by the fixture test harness. Feed it the `outerHTML` dumped from the
 * WebView (or a recorded fixture) and it returns typed data or a typed failure — it
 * NEVER throws on unexpected markup (ADR-008).
 */
object RuleExtractor {
    /**
     * Runs [rule]'s extract map (and row extraction) against [html].
     *
     * Failure conditions (any one triggers [ExtractionResult.Failure] with the raw
     * HTML attached): a `required` field resolves blank; fewer rows than
     * [RowExtract.minRows]; nothing at all was extracted.
     */
    fun extract(
        rule: ScrapeRule,
        html: String,
    ): ExtractionResult =
        runCatching { extractOrThrow(rule, html) }
            .getOrElse { throwable ->
                ExtractionResult.Failure(
                    reason = "Extraction error: ${throwable.message ?: throwable.javaClass.simpleName}",
                    rawHtml = html,
                )
            }

    private fun extractOrThrow(
        rule: ScrapeRule,
        html: String,
    ): ExtractionResult {
        val document = Jsoup.parse(html)

        val fields = mutableMapOf<String, String>()
        for ((name, spec) in rule.extract) {
            val value = evaluate(spec, document)
            if (spec.required && value.isBlank()) {
                return ExtractionResult.Failure(
                    reason = "Required field '$name' is missing or blank (selector: ${spec.selector})",
                    rawHtml = html,
                )
            }
            fields[name] = value
        }

        val rows = mutableListOf<Map<String, String>>()
        val rowSpec = rule.rows
        if (rowSpec != null) {
            for (rowElement in document.select(rowSpec.rowSelector)) {
                rows +=
                    rowSpec.fields.mapValues { (_, spec) ->
                        evaluate(spec, rowElement)
                    }
            }
            if (rows.size < rowSpec.minRows) {
                return ExtractionResult.Failure(
                    reason =
                        "Expected at least ${rowSpec.minRows} row(s) for selector " +
                            "'${rowSpec.rowSelector}' but found ${rows.size}",
                    rawHtml = html,
                )
            }
        }

        if (fields.values.all(String::isBlank) && rows.isEmpty()) {
            return ExtractionResult.Failure(
                reason = "Nothing extracted — page markup likely changed",
                rawHtml = html,
            )
        }

        val processed = fields.mapValues { (name, value) -> postProcess(rule.postProcess[name], value) }
        return ExtractionResult.Success(ScrapedData(fields = processed, rows = rows))
    }

    /** Resolves one [ExtractSpec] relative to [scope] (whole document or a row). */
    private fun evaluate(
        spec: ExtractSpec,
        scope: Element,
    ): String {
        var value =
            if (spec.selector != null) {
                val element = scope.selectFirst(spec.selector) ?: return ""
                if (spec.attribute != null) element.attr(spec.attribute).trim() else element.text().trim()
            } else {
                if (spec.attribute != null) scope.attr(spec.attribute).trim() else scope.text().trim()
            }
        for (pattern in spec.regexChain) {
            val match = runCatching { Regex(pattern).find(value) }.getOrNull()
            value =
                when {
                    match == null -> ""
                    match.groupValues.size > 1 -> match.groupValues[1]
                    else -> match.value
                }
            if (value.isEmpty()) break
        }
        return value.trim()
    }

    /** Best effort: an unparseable value keeps its raw form — never a failure. */
    private fun postProcess(
        hint: PostProcessHint?,
        value: String,
    ): String {
        if (hint == null || value.isBlank()) return value
        return when (hint.type) {
            "trim" -> value.trim().replace(Regex("\\s+"), " ")
            "date" -> reformat(value, hint, default = "yyyy-MM-dd", parse = LocalDate::parse, format = LocalDate::format)
            "time" -> reformat(value, hint, default = "HH:mm", parse = LocalTime::parse, format = LocalTime::format)
            else -> value
        }
    }

    private inline fun <T> reformat(
        value: String,
        hint: PostProcessHint,
        default: String,
        parse: (String, DateTimeFormatter) -> T,
        format: T.(DateTimeFormatter) -> String,
    ): String {
        val output =
            runCatching { DateTimeFormatter.ofPattern(hint.outputFormat ?: default) }
                .getOrNull() ?: return value
        for (pattern in hint.inputFormats) {
            val parsed =
                runCatching { parse(value, DateTimeFormatter.ofPattern(pattern)) }
                    .getOrNull() ?: continue
            return runCatching { parsed.format(output) }.getOrDefault(value)
        }
        return value
    }
}
