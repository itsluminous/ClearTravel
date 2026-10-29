package com.itsluminous.cleartravel.feature.itinerary.logic

import com.itsluminous.cleartravel.core.model.ItineraryItem
import com.itsluminous.cleartravel.core.model.ItineraryItemType

/** A plus code found in place text, with the locality text that anchors its short form. */
data class PlusCodeMention(
    val code: String,
    /** "Lucknow, Uttar Pradesh" — what to geocode for the short code's reference point; null when the text has no locality. */
    val locality: String?,
)

/**
 * Pure helpers of the place-location ladder (ADR-048). Google Maps shares a place as
 * `Name, street, area, City, State PIN` and the geocoders want progressively less of
 * it: Nominatim matches structured OSM data and chokes on parentheticals and house
 * numbers, the platform geocoder likes the full line. Everything here is derived
 * from the text alone, so the ladder is unit-tested without a network.
 */
object PlaceQueries {
    /** The ladder never fires more than this many geocode queries per item (Nominatim is 1 req/s). */
    const val MAX_CANDIDATES = 3

    /**
     * Geocode queries for [text], most specific first and de-duplicated: the full text,
     * `name, locality, region`, then `name, locality` — where `name` is the first
     * comma segment with parentheticals and a leading plus code removed, `locality` the
     * last segment before the postal/country tail and `region` that tail minus its
     * postal code. A text without a comma yields only itself.
     */
    fun geocodeCandidates(text: String): List<String> {
        val full = text.trim().replace(WHITESPACE, " ")
        if (full.isEmpty()) return emptyList()
        val parts = addressParts(full) ?: return listOf(full)
        val candidates = mutableListOf(full)
        parts.region?.let { region -> candidates += "${parts.name}, ${parts.locality}, $region" }
        candidates += "${parts.name}, ${parts.locality}"
        return candidates.distinct().take(MAX_CANDIDATES)
    }

    /** The plus code in [text] (short or full), if any, with the locality to anchor it. */
    fun plusCodeMention(text: String): PlusCodeMention? {
        val code =
            OpenLocationCode.CODE_PATTERN
                .findAll(text)
                .map { it.value.uppercase() }
                .firstOrNull { OpenLocationCode.isValid(it) } ?: return null
        val parts = addressParts(text.trim())
        val locality = parts?.let { listOfNotNull(it.locality, it.region).joinToString(", ") }?.takeIf(String::isNotBlank)
        return PlusCodeMention(code = code, locality = locality)
    }

    /**
     * Backfill eligibility: a LIVE place with no coordinates that carries either a
     * Google Maps link or an address-like name (two or more comma segments). A bare
     * one-word name ("Lunch") is NOT geocoded — a wrong pin is worse than none; the
     * user places those by hand.
     */
    fun isUnplaced(item: ItineraryItem): Boolean =
        item.deletedAt == null &&
            item.type == ItineraryItemType.PLACE &&
            (item.latitude == null || item.longitude == null) &&
            (MapsLinks.isMapsLink(item.link) || addressParts(item.name) != null)

    private data class AddressParts(
        val name: String,
        val locality: String,
        val region: String?,
    )

    private fun addressParts(text: String): AddressParts? {
        val segments = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (segments.size < 2) return null
        val name = cleanName(segments.first()).ifEmpty { return null }
        var tail = segments.drop(1)
        var region: String? = null
        // Peel the postal/country tail: "Uttar Pradesh 226003", "NY 10001, USA".
        while (tail.size > 1 && (tail.last().matches(COUNTRY) || POSTAL_CODE.containsMatchIn(tail.last()))) {
            val stripped =
                tail
                    .last()
                    .replace(POSTAL_CODE, "")
                    .trim()
                    .trim(',', '-')
                    .trim()
            if (region == null && stripped.isNotEmpty() && !stripped.matches(COUNTRY)) region = stripped
            tail = tail.dropLast(1)
        }
        val localityRaw = tail.lastOrNull { !it.matches(COUNTRY) } ?: return null
        val locality =
            localityRaw
                .replace(POSTAL_CODE, "")
                .trim()
                .trim(',', '-')
                .trim()
                .ifEmpty { return null }
        if (locality.equals(name, ignoreCase = true)) return null
        return AddressParts(name = name, locality = locality, region = region)
    }

    private fun cleanName(segment: String): String =
        segment
            .replace(PARENTHETICAL, " ")
            .replace(OpenLocationCode.CODE_PATTERN, " ")
            .replace(WHITESPACE, " ")
            .trim()

    private val WHITESPACE = Regex("""\s+""")
    private val PARENTHETICAL = Regex("""\([^)]*\)|\[[^]]*]""")

    /** 5–6 digit PIN / ZIP (optionally `12345-6789`), or a UK-style outward+inward code. */
    private val POSTAL_CODE = Regex("""\b\d{5,6}(?:-\d{4})?\b|\b[A-Z]{1,2}\d[A-Z\d]? ?\d[A-Z]{2}\b""")
    private val COUNTRY =
        Regex(
            "(?i)^(india|usa|u\\.s\\.a\\.|united states|united kingdom|uk|canada|australia|germany|france|italy|spain|" +
                "japan|singapore|uae|united arab emirates|nepal|sri lanka|bangladesh|thailand|indonesia|malaysia|" +
                "switzerland|netherlands|new zealand|ireland|portugal|turkey|vietnam)$",
        )
}
