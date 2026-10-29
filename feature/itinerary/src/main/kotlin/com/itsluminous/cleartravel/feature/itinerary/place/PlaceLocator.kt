package com.itsluminous.cleartravel.feature.itinerary.place

import com.itsluminous.cleartravel.feature.itinerary.intake.MapsLinkResolver
import com.itsluminous.cleartravel.feature.itinerary.logic.MapPoint
import com.itsluminous.cleartravel.feature.itinerary.logic.MapsLinks
import com.itsluminous.cleartravel.feature.itinerary.logic.OpenLocationCode
import com.itsluminous.cleartravel.feature.itinerary.logic.PlaceGeocoder
import com.itsluminous.cleartravel.feature.itinerary.logic.PlaceQueries
import javax.inject.Inject

/** Which rung of the ladder produced the coordinates. */
enum class PlaceSource {
    /** Parsed from the (expanded) Google Maps link itself. */
    LINK,

    /** Geocoded from the place text (full text, then `name, locality`). */
    GEOCODE,

    /** A plus code in the text, anchored to the geocoded locality. */
    PLUS_CODE,
}

/** The ladder's answer: a point, how it was found, and the place name if a link named it. */
data class LocatedPlace(
    val point: MapPoint,
    val source: PlaceSource,
    /** The `/place/<name>/` text of an expanded link, when the link carried one. */
    val linkName: String? = null,
)

/**
 * The place-location ladder (ADR-048): a Google Maps link and/or the place text in,
 * coordinates out — or null. Never throws; every rung swallows its own failures. Any
 * caller runs it off the UI thread and keeps the UI usable meanwhile.
 */
interface PlaceLocator {
    suspend fun locate(
        link: String?,
        text: String?,
    ): LocatedPlace?
}

/**
 * Rungs, in order:
 * 1. **Link.** Parse the link; a short link is expanded first (`MapsLinkResolver`). Only
 *    coordinates IN THE URL count (`!3d!4d`, `@lat,lng`, `q=lat,lng`…). The landing
 *    page HTML is deliberately NOT read: on the real pages the only coordinates in it
 *    (`APP_INITIALIZATION_STATE`, the `og:image` static map `center=`) are the
 *    requester's IP location, not the place — see `MapsLinksRealRedirectsTest`.
 * 2. **Geocode.** `PlaceQueries.geocodeCandidates(text)` through the paced geocoder
 *    (Nominatim, platform fallback), first hit wins. The text is the item's own, else
 *    the name the expanded link carried.
 * 3. **Plus code.** A short code is anchored to the geocoded locality. (A FULL code is
 *    exact and decodes offline, so it is taken before rung 2.)
 */
class DefaultPlaceLocator
    @Inject
    constructor(
        private val resolver: MapsLinkResolver,
        private val geocoder: PlaceGeocoder,
        private val pacer: GeocodePacer,
    ) : PlaceLocator {
        override suspend fun locate(
            link: String?,
            text: String?,
        ): LocatedPlace? {
            var linkName: String? = null
            val mapsUrl = link?.takeIf(String::isNotBlank)?.let(MapsLinks::extractUrl)
            if (mapsUrl != null) {
                var place = MapsLinks.parse(mapsUrl)
                if (!place.hasLocation && MapsLinks.isShortLink(mapsUrl)) {
                    val expanded = runCatching { resolver.resolve(mapsUrl) }.getOrNull()
                    if (expanded != null) place = MapsLinks.parse(expanded)
                }
                linkName = place.name?.takeIf(String::isNotBlank)
                if (place.hasLocation) {
                    return LocatedPlace(MapPoint(place.latitude!!, place.longitude!!), PlaceSource.LINK, linkName)
                }
            }
            val placeText = text?.takeIf(String::isNotBlank) ?: linkName ?: return null
            val mention = PlaceQueries.plusCodeMention(placeText)
            // A FULL plus code is exact and needs no network: it outranks the geocoders.
            if (mention != null && OpenLocationCode.isFull(mention.code)) {
                OpenLocationCode.decode(mention.code)?.let { area ->
                    return LocatedPlace(MapPoint(area.latitude, area.longitude), PlaceSource.PLUS_CODE, linkName)
                }
            }
            for (query in PlaceQueries.geocodeCandidates(placeText)) {
                val hit = search(query) ?: continue
                return LocatedPlace(hit, PlaceSource.GEOCODE, linkName)
            }
            // A SHORT code needs a reference point: the geocoded locality.
            val locality = mention?.locality ?: return null
            val reference = search(locality) ?: return null
            val fullCode = OpenLocationCode.recoverNearest(mention.code, reference.latitude, reference.longitude) ?: return null
            val area = OpenLocationCode.decode(fullCode) ?: return null
            return LocatedPlace(MapPoint(area.latitude, area.longitude), PlaceSource.PLUS_CODE, linkName)
        }

        private suspend fun search(query: String): MapPoint? =
            pacer
                .paced { runCatching { geocoder.search(query) }.getOrDefault(emptyList()) }
                .firstOrNull()
                ?.point
    }
