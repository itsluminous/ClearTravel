package com.itsluminous.cleartravel.feature.flights.checkin

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Airline check-in behavior as DATA (ADR-003): `assets/checkin-windows.json` carries
 * a default open/close window plus per-airline overrides and the airline's web
 * check-in deep link + display name. Updating an airline's rules means editing the
 * JSON file only; [CheckInWindowsAssetTest] is its fixture test.
 */
@Serializable
data class CheckInRules(
    val version: Int,
    val defaultWindow: CheckInWindowSpec,
    /** Keyed by airline IATA code, uppercase (e.g. "6E"). */
    val airlines: Map<String, AirlineCheckInInfo> = emptyMap(),
) {
    /** Override when present, default otherwise. Lookup is case-insensitive. */
    fun windowFor(airlineIata: String): CheckInWindowSpec = infoFor(airlineIata)?.toSpec() ?: defaultWindow

    fun infoFor(airlineIata: String): AirlineCheckInInfo? = airlines[airlineIata.trim().uppercase()]

    /** Airline display name; null for airlines not in the table. */
    fun airlineName(airlineIata: String): String? = infoFor(airlineIata)?.name

    /** Airline web check-in URL; null for airlines not in the table. */
    fun checkInUrl(airlineIata: String): String? = infoFor(airlineIata)?.checkInUrl

    companion object {
        val EMPTY = CheckInRules(version = 0, defaultWindow = CheckInWindowSpec(48, 1))
    }
}

@Serializable
data class CheckInWindowSpec(
    val opensHoursBefore: Int,
    val closesHoursBefore: Int,
)

@Serializable
data class AirlineCheckInInfo(
    val name: String,
    val opensHoursBefore: Int,
    val closesHoursBefore: Int,
    val checkInUrl: String? = null,
) {
    fun toSpec(): CheckInWindowSpec = CheckInWindowSpec(opensHoursBefore, closesHoursBefore)
}

/** The concrete open..close instant range for one flight. */
data class CheckInWindow(
    val opensAt: Instant,
    val closesAt: Instant,
) {
    /** Whether check-in is open at [now] (open-inclusive, close-exclusive). */
    fun isOpenAt(now: Instant): Boolean = !now.isBefore(opensAt) && now.isBefore(closesAt)
}

/**
 * PURE check-in window computation: [scheduledDeparture] minus the airline's
 * open/close offsets. Null when the flight has no scheduled departure yet.
 */
fun computeCheckInWindow(
    rules: CheckInRules,
    airlineIata: String,
    scheduledDeparture: Instant?,
): CheckInWindow? {
    if (scheduledDeparture == null) return null
    val spec = rules.windowFor(airlineIata)
    return CheckInWindow(
        opensAt = scheduledDeparture.minusSeconds(spec.opensHoursBefore * SECONDS_PER_HOUR),
        closesAt = scheduledDeparture.minusSeconds(spec.closesHoursBefore * SECONDS_PER_HOUR),
    )
}

private const val SECONDS_PER_HOUR = 3600L

/** Parses the rules JSON; tolerant of unknown keys so the data file can grow. */
object CheckInRulesParser {
    private val json = Json { ignoreUnknownKeys = true }

    /** Never throws — unreadable/invalid content degrades to [CheckInRules.EMPTY]. */
    fun parse(text: String): CheckInRules =
        runCatching { json.decodeFromString(CheckInRules.serializer(), text) }
            .getOrDefault(CheckInRules.EMPTY)
}

/** Where the rules JSON comes from — asset-backed at runtime, string-backed in tests. */
interface CheckInRuleSource {
    fun load(): CheckInRules
}

/** Production source reading `assets/checkin-windows.json` (cached after first read). */
@Singleton
class AssetCheckInRuleSource
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : CheckInRuleSource {
        private val rules: CheckInRules by lazy {
            runCatching {
                context.assets
                    .open(ASSET_PATH)
                    .bufferedReader()
                    .use { it.readText() }
            }.map(CheckInRulesParser::parse).getOrDefault(CheckInRules.EMPTY)
        }

        override fun load(): CheckInRules = rules

        companion object {
            const val ASSET_PATH = "checkin-windows.json"
        }
    }
