package com.itsluminous.cleartravel.core.ocr.extract

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One airline in [FlightDocumentRules]: display name plus the aliases documents print. */
@Serializable
data class FlightDocumentAirline(
    val name: String,
    val aliases: List<String> = emptyList(),
)

/**
 * Behavior-as-data (ADR-003) for the flight-document extractors (ADR-045): the
 * airline alias table that resolves "Air India Express 2019" to `IX 2019` when a
 * confirmation prints the airline's NAME instead of its code, plus the word lists
 * that keep look-alikes from passing as codes. Loaded from the module's
 * `flight-document-rules.json` resource; the sync test keeps the airline table
 * identical to `feature:flights`' SMS rules.
 */
@Serializable
data class FlightDocumentRules(
    val version: Int,
    val notes: String = "",
    /** Keyed by airline IATA code, uppercase. */
    val airlines: Map<String, FlightDocumentAirline> = emptyMap(),
    /** Two-letter words that look like carrier codes but never are (`ON 29`, `RS 4520`). */
    val flightCodeStopwords: List<String> = emptyList(),
    /** Three-letter upper-case words that look like IATA codes but never are (`PNR`, `INR`). */
    val iataStopwords: List<String> = emptyList(),
) {
    /** `(alias regex, code)` pairs, longest alias first so "Air India Express" never reads as "Air India". */
    val aliasPatterns: List<Pair<Regex, String>> by lazy {
        airlines
            .flatMap { (code, info) -> (info.aliases + info.name).distinct().map { alias -> alias to code } }
            .sortedByDescending { it.first.length }
            .map { (alias, code) -> aliasRegex(alias) to code }
    }

    /** The airline whose alias appears anywhere in [upperText], or null. */
    fun airlineByAlias(upperText: String): String? = aliasPatterns.firstOrNull { (regex, _) -> regex.containsMatchIn(upperText) }?.second

    private fun aliasRegex(alias: String): Regex {
        val words =
            alias
                .trim()
                .uppercase()
                .split(Regex("\\s+"))
                .map { Regex.escape(it) }
        return Regex("""\b${words.joinToString("""\s*""")}\b""")
    }

    companion object {
        private const val RESOURCE = "/flight-document-rules.json"
        private val json = Json { ignoreUnknownKeys = true }

        /** The bundled rules; a missing/corrupt resource degrades to an EMPTY table, never a crash. */
        val BUNDLED: FlightDocumentRules by lazy {
            runCatching {
                val text =
                    requireNotNull(FlightDocumentRules::class.java.getResourceAsStream(RESOURCE)) {
                        "Missing resource $RESOURCE"
                    }.bufferedReader().use { it.readText() }
                parse(text)
            }.getOrDefault(FlightDocumentRules(version = 0))
        }

        fun parse(text: String): FlightDocumentRules = json.decodeFromString(text)
    }
}
