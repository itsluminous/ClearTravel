package com.itsluminous.cleartravel.feature.itinerary.logic

import java.net.URLDecoder

/**
 * What a shared Google Maps link tells us about a place (ADR-029 part D). Any field
 * may be missing: a short link carries nothing until resolved, a search link may name
 * a place without pinning it, a plain pin may have coordinates and no name.
 */
data class MapsPlace(
    val url: String,
    val name: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
) {
    val hasLocation: Boolean get() = latitude != null && longitude != null
}

/**
 * Pure parsing of Google Maps share links (ADR-029 part D). No Android types so the
 * whole surface is unit-tested; the ViewModel handles I/O (short-link resolution).
 *
 * Recognised hosts: `maps.app.goo.gl` and `goo.gl/maps/…` (short links),
 * `maps.google.<tld>`, and `google.<tld>/maps…` (with or without `www.`).
 *
 * Coordinates, most precise first: the `!3d<lat>!4d<lng>` place pin inside the
 * `data=` blob; `@<lat>,<lng>` (the viewport centre); a `lat,lng` value of `q`,
 * `query`, `ll`, `center`, `destination` or `daddr`. Name: the `/place/<name>/` path
 * segment, else a non-coordinate `q`/`query`/`destination` value — both `%`-decoded
 * with `+` → space.
 */
object MapsLinks {
    /** The first Google Maps URL in [text] (shared text often wraps the link in a sentence), or null. */
    fun extractUrl(text: String): String? =
        URL_PATTERN
            .findAll(text)
            .map { it.value.trimEnd('.', ',', ')', ']', '>') }
            .firstOrNull { isMapsUrl(it) }

    /** Share-sheet classifier: does this shared text carry a Google Maps link? */
    fun isMapsLink(text: String): Boolean = extractUrl(text) != null

    /** Short links carry no data in the URL itself: resolve the redirect first. */
    fun isShortLink(url: String): Boolean {
        val host = hostOf(url) ?: return false
        return host == "maps.app.goo.gl" || (host == "goo.gl" && pathOf(url).startsWith("/maps"))
    }

    /** Everything derivable from [url] alone. Never throws; unknown parts are null. */
    fun parse(url: String): MapsPlace {
        val path = pathOf(url)
        val params = queryParams(url)
        val pin = PIN_PATTERN.find(url)?.let { coordinates(it.groupValues[1], it.groupValues[2]) }
        val centre = AT_PATTERN.find(url)?.let { coordinates(it.groupValues[1], it.groupValues[2]) }
        val fromQuery =
            COORDINATE_PARAMS
                .mapNotNull { key -> params[key] }
                .firstNotNullOfOrNull { value ->
                    LAT_LNG_PATTERN.matchEntire(value.trim())?.let { coordinates(it.groupValues[1], it.groupValues[2]) }
                }
        val location = pin ?: centre ?: fromQuery
        val name =
            PLACE_PATTERN
                .find(path)
                ?.groupValues
                ?.get(1)
                ?.let(::decode)
                ?.takeIf(String::isNotBlank)
                ?: NAME_PARAMS
                    .mapNotNull { key -> params[key] }
                    .firstOrNull { value -> LAT_LNG_PATTERN.matchEntire(value.trim()) == null && value.isNotBlank() }
                    ?.trim()
        return MapsPlace(url = url, name = name, latitude = location?.first, longitude = location?.second)
    }

    private fun isMapsUrl(url: String): Boolean {
        val host = hostOf(url) ?: return false
        if (isShortLink(url)) return true
        if (host.startsWith("maps.google.")) return true
        val bare = host.removePrefix("www.")
        return bare.startsWith("google.") && pathOf(url).startsWith("/maps")
    }

    private fun hostOf(url: String): String? =
        HOST_PATTERN
            .find(url)
            ?.groupValues
            ?.get(1)
            ?.lowercase()

    private fun pathOf(url: String): String {
        val afterScheme = url.substringAfter("://", "")
        val hostEnd = afterScheme.indexOfFirst { it == '/' || it == '?' || it == '#' }
        if (hostEnd < 0) return "/"
        val rest = afterScheme.substring(hostEnd)
        return if (rest.startsWith("/")) rest.substringBefore('?').substringBefore('#') else "/"
    }

    private fun queryParams(url: String): Map<String, String> {
        val query = url.substringAfter('?', "").substringBefore('#')
        if (query.isEmpty()) return emptyMap()
        return query
            .split('&')
            .filter { it.isNotBlank() }
            .associate { pair ->
                val key = pair.substringBefore('=')
                val value = pair.substringAfter('=', "")
                decode(key) to decode(value)
            }
    }

    private fun coordinates(
        lat: String,
        lng: String,
    ): Pair<Double, Double>? {
        val latitude = lat.toDoubleOrNull() ?: return null
        val longitude = lng.toDoubleOrNull() ?: return null
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
        return latitude to longitude
    }

    private fun decode(text: String): String = runCatching { URLDecoder.decode(text, "UTF-8") }.getOrDefault(text.replace('+', ' '))

    private val URL_PATTERN = Regex("""https?://[^\s<>"']+""")
    private val HOST_PATTERN = Regex("""^https?://([^/?#:]+)""", RegexOption.IGNORE_CASE)
    private val PLACE_PATTERN = Regex("""/place/([^/]+)""")
    private val PIN_PATTERN = Regex("""!3d(-?\d+(?:\.\d+)?)!4d(-?\d+(?:\.\d+)?)""")
    private val AT_PATTERN = Regex("""@(-?\d+(?:\.\d+)?),(-?\d+(?:\.\d+)?)""")
    private val LAT_LNG_PATTERN = Regex("""(?:loc:)?(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)""")
    private val COORDINATE_PARAMS = listOf("q", "query", "ll", "center", "destination", "daddr")
    private val NAME_PARAMS = listOf("q", "query", "destination")
}
