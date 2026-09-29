package com.itsluminous.cleartravel.feature.flights.text

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * How flight SMS/email text is read, as DATA (ADR-003, ADR-042):
 * `assets/airline-sms-rules.json` carries the airline code → name/alias table, the
 * stop-word lists that keep ordinary words from passing as codes, and the ordered
 * regex lists [FlightTextParser] applies. Updating an airline's wording means editing
 * the JSON only; the fixtures under `test/resources/flight-sms` are its regression tests.
 */
@Serializable
data class AirlineSmsRules(
    val version: Int,
    /** Keyed by airline IATA code, uppercase (e.g. "6E"). */
    val airlines: Map<String, AirlineSmsInfo> = emptyMap(),
    /** Two-letter words that look like carrier codes but never are ("ON 29"). */
    val flightCodeStopwords: List<String> = emptyList(),
    /** Three-letter upper-case words that look like IATA codes but never are ("PNR"). */
    val iataStopwords: List<String> = emptyList(),
    /** Greeting targets that are not names ("Dear Customer"). */
    val passengerStopwords: List<String> = emptyList(),
    /** Generic pattern lists; a recognised airline's own lists are prepended. */
    val patterns: AirlineSmsPatterns = AirlineSmsPatterns(),
) {
    fun infoFor(code: String): AirlineSmsInfo? = airlines[code.trim().uppercase()]

    /** Airline display name; null for airlines not in the table. */
    fun airlineName(code: String): String? = infoFor(code)?.name

    /**
     * The airline whose alias appears in [text], longest alias first so "Air India
     * Express" is never read as "Air India"; null when no alias matches.
     */
    fun airlineByAlias(text: String): String? =
        airlines
            .flatMap { (code, info) -> info.aliases.map { alias -> alias to code } }
            .sortedByDescending { it.first.length }
            .firstOrNull { (alias, _) -> aliasRegex(alias).containsMatchIn(text) }
            ?.second

    /** Generic patterns with [airlineCode]'s own (if any) in front. */
    fun patternsFor(airlineCode: String?): AirlineSmsPatterns {
        val own = airlineCode?.let { infoFor(it)?.patterns } ?: return patterns
        return own + patterns
    }

    private fun aliasRegex(alias: String): Regex = Regex("""(?i)(?<![A-Za-z])${Regex.escape(alias)}(?![A-Za-z])""")

    companion object {
        val EMPTY = AirlineSmsRules(version = 0)
    }
}

@Serializable
data class AirlineSmsInfo(
    val name: String,
    val aliases: List<String> = emptyList(),
    /** Airline-specific wording, tried BEFORE the generic patterns. */
    val patterns: AirlineSmsPatterns = AirlineSmsPatterns(),
)

/**
 * Ordered regex lists (first match wins). Group 1 carries the value; two-value
 * patterns (route, flight) carry from/carrier in group 1 and to/number in group 2;
 * time patterns carry an optional am/pm in group 2.
 */
@Serializable
data class AirlineSmsPatterns(
    val labeledFlight: List<String> = emptyList(),
    val bareFlight: List<String> = emptyList(),
    val labeledPnr: List<String> = emptyList(),
    val barePnr: List<String> = emptyList(),
    val route: List<String> = emptyList(),
    val labeledDate: List<String> = emptyList(),
    val depTime: List<String> = emptyList(),
    val arrTime: List<String> = emptyList(),
    val anyTime: List<String> = emptyList(),
    val terminal: List<String> = emptyList(),
    val passenger: List<String> = emptyList(),
    val seat: List<String> = emptyList(),
    val cabin: List<String> = emptyList(),
    val bareCabin: List<String> = emptyList(),
) {
    operator fun plus(other: AirlineSmsPatterns): AirlineSmsPatterns =
        AirlineSmsPatterns(
            labeledFlight = labeledFlight + other.labeledFlight,
            bareFlight = bareFlight + other.bareFlight,
            labeledPnr = labeledPnr + other.labeledPnr,
            barePnr = barePnr + other.barePnr,
            route = route + other.route,
            labeledDate = labeledDate + other.labeledDate,
            depTime = depTime + other.depTime,
            arrTime = arrTime + other.arrTime,
            anyTime = anyTime + other.anyTime,
            terminal = terminal + other.terminal,
            passenger = passenger + other.passenger,
            seat = seat + other.seat,
            cabin = cabin + other.cabin,
            bareCabin = bareCabin + other.bareCabin,
        )

    /** Every pattern list, for the asset test's "all regexes compile" check. */
    fun all(): List<String> =
        labeledFlight + bareFlight + labeledPnr + barePnr + route + labeledDate + depTime + arrTime + anyTime +
            terminal + passenger + seat + cabin + bareCabin
}

/** Parses the rules JSON; tolerant of unknown keys so the data file can grow. */
object AirlineSmsRulesParser {
    private val json = Json { ignoreUnknownKeys = true }

    /** Never throws — unreadable/invalid content degrades to [AirlineSmsRules.EMPTY]. */
    fun parse(text: String): AirlineSmsRules =
        runCatching { json.decodeFromString(AirlineSmsRules.serializer(), text) }
            .getOrDefault(AirlineSmsRules.EMPTY)
}

/** Where the rules JSON comes from — asset-backed at runtime, value-backed in tests. */
fun interface AirlineSmsRuleSource {
    fun load(): AirlineSmsRules
}

/** Production source reading `assets/airline-sms-rules.json` (cached after first read). */
@Singleton
class AssetAirlineSmsRuleSource
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : AirlineSmsRuleSource {
        private val rules: AirlineSmsRules by lazy {
            runCatching {
                context.assets
                    .open(ASSET_PATH)
                    .bufferedReader()
                    .use { it.readText() }
            }.map(AirlineSmsRulesParser::parse).getOrDefault(AirlineSmsRules.EMPTY)
        }

        override fun load(): AirlineSmsRules = rules

        companion object {
            const val ASSET_PATH = "airline-sms-rules.json"
        }
    }
