package com.itsluminous.cleartravel.feature.itinerary.fakes

import com.itsluminous.cleartravel.feature.itinerary.logic.GeocodedPlace
import com.itsluminous.cleartravel.feature.itinerary.logic.MapPoint
import com.itsluminous.cleartravel.feature.itinerary.logic.PlaceGeocoder
import com.itsluminous.cleartravel.feature.itinerary.place.GeocodePacer
import com.itsluminous.cleartravel.feature.itinerary.place.LocatedPlace
import com.itsluminous.cleartravel.feature.itinerary.place.NetworkAvailability
import com.itsluminous.cleartravel.feature.itinerary.place.PlaceLocator
import com.itsluminous.cleartravel.feature.itinerary.place.PlaceSource
import kotlinx.coroutines.yield

/** Scripted [PlaceGeocoder]: exact-query answers, every query recorded in order. */
class FakePlaceGeocoder : PlaceGeocoder {
    val answers = mutableMapOf<String, List<GeocodedPlace>>()
    val queries = mutableListOf<String>()

    fun answer(
        query: String,
        latitude: Double,
        longitude: Double,
        label: String = query,
    ) {
        answers[query] = listOf(GeocodedPlace(label, MapPoint(latitude, longitude)))
    }

    override suspend fun search(query: String): List<GeocodedPlace> {
        queries += query
        return answers[query].orEmpty()
    }
}

/** Scripted [PlaceLocator]: answers keyed by the place text (or link when text is null). */
class FakePlaceLocator : PlaceLocator {
    val answers = mutableMapOf<String, LocatedPlace>()
    val calls = mutableListOf<Pair<String?, String?>>()

    fun answer(
        key: String,
        latitude: Double,
        longitude: Double,
        source: PlaceSource = PlaceSource.GEOCODE,
    ) {
        answers[key] = LocatedPlace(MapPoint(latitude, longitude), source)
    }

    override suspend fun locate(
        link: String?,
        text: String?,
    ): LocatedPlace? {
        calls += link to text
        // A real ladder suspends on the network; let observers run between items.
        yield()
        return answers[text ?: ""] ?: answers[link ?: ""]
    }
}

class FakeNetworkAvailability(
    var online: Boolean = true,
) : NetworkAvailability {
    override fun isOnline(): Boolean = online
}

/** A pacer over VIRTUAL time: a sleep advances the clock and is recorded instead of waiting. */
class VirtualPacer {
    var now = 0L
    val waits = mutableListOf<Long>()
    val pacer: GeocodePacer =
        GeocodePacer(
            now = { now },
            sleep = { millis ->
                waits += millis
                now += millis
            },
        )
}
