package com.itsluminous.cleartravel.feature.itinerary.logic

import android.content.Context
import android.location.Geocoder
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.resume

/** One geocoding hit for the picker's search box. */
data class GeocodedPlace(
    val label: String,
    val point: MapPoint,
)

/** Seam over platform geocoding so the search state is unit-testable. */
fun interface PlaceGeocoder {
    /** Resolves [query] to candidate places; empty on no match, never throws. */
    suspend fun search(query: String): List<GeocodedPlace>
}

private const val MAX_RESULTS = 5

/**
 * Platform [Geocoder]-backed search (present on Play-services devices; no extra
 * API key or billing — deliberately NOT the Places SDK). Absent/failed geocoder
 * degrades to an empty result, which the UI reports as "no matches".
 */
fun platformPlaceGeocoder(context: Context): PlaceGeocoder =
    PlaceGeocoder { query ->
        val trimmed = query.trim()
        if (trimmed.isEmpty() || !Geocoder.isPresent()) return@PlaceGeocoder emptyList()
        val geocoder = Geocoder(context, Locale.getDefault())
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                suspendCancellableCoroutine { cont ->
                    geocoder.getFromLocationName(trimmed, MAX_RESULTS) { addresses ->
                        cont.resume(addresses.toList())
                    }
                }
            } else {
                withContext(Dispatchers.IO) {
                    @Suppress("DEPRECATION")
                    geocoder.getFromLocationName(trimmed, MAX_RESULTS).orEmpty()
                }
            }
        }.getOrDefault(emptyList())
            .mapNotNull { address ->
                val label =
                    (0..address.maxAddressLineIndex)
                        .joinToString(", ") { address.getAddressLine(it) }
                        .ifEmpty { address.featureName.orEmpty() }
                if (label.isEmpty()) {
                    null
                } else {
                    GeocodedPlace(label = label, point = MapPoint(address.latitude, address.longitude))
                }
            }
    }
