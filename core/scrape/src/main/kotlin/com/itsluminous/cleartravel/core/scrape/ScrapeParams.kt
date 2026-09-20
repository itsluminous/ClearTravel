package com.itsluminous.cleartravel.core.scrape

/**
 * Runtime values expanded into a rule's `{pnr}` / `{flightNumber}` / `{date}` /
 * `{trainNumber}` placeholders. [date] is whatever format the target site expects;
 * rules that need a specific shape document it in their JSON file.
 */
data class ScrapeParams(
    val pnr: String? = null,
    val flightNumber: String? = null,
    val date: String? = null,
    /** Bare train number for schedule/route rules (e.g. `22346`). Additive, ADR-018. */
    val trainNumber: String? = null,
) {
    /** Expands the known placeholders; unknown placeholders are left untouched. */
    fun expand(template: String): String =
        template
            .replace(PLACEHOLDER_PNR, pnr.orEmpty())
            .replace(PLACEHOLDER_FLIGHT_NUMBER, flightNumber.orEmpty())
            .replace(PLACEHOLDER_DATE, date.orEmpty())
            .replace(PLACEHOLDER_TRAIN_NUMBER, trainNumber.orEmpty())

    private companion object {
        const val PLACEHOLDER_PNR = "{pnr}"
        const val PLACEHOLDER_FLIGHT_NUMBER = "{flightNumber}"
        const val PLACEHOLDER_DATE = "{date}"
        const val PLACEHOLDER_TRAIN_NUMBER = "{trainNumber}"
    }
}

/** Escapes a value for embedding inside a single-quoted JavaScript string literal. */
internal fun String.escapeForJsSingleQuotedString(): String =
    buildString(length) {
        for (ch in this@escapeForJsSingleQuotedString) {
            when (ch) {
                '\\' -> append("\\\\")
                '\'' -> append("\\'")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '<' -> append("\\u003C")
                else -> append(ch)
            }
        }
    }
