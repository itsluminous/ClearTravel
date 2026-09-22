package com.itsluminous.cleartravel.feature.itinerary.logic

import android.content.Context
import android.location.Geocoder
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** One geocoding hit for the picker's search box. */
data class GeocodedPlace(
    val label: String,
    val point: MapPoint,
)

/** Seam over geocoding so the search UI is unit-testable. */
fun interface PlaceGeocoder {
    /** Resolves [query] to candidate places; empty on no match, never throws. */
    suspend fun search(query: String): List<GeocodedPlace>
}

private const val MAX_RESULTS = 5
private const val NOMINATIM_URL = "https://nominatim.openstreetmap.org/search"
private const val TIMEOUT_SECONDS = 8L

@Serializable
internal data class NominatimHit(
    @SerialName("display_name") val displayName: String = "",
    val lat: String = "",
    val lon: String = "",
)

private val json = Json { ignoreUnknownKeys = true }

/** PURE parser for a Nominatim response body — unit-tested. */
internal fun parseNominatimResponse(body: String): List<GeocodedPlace> =
    runCatching { json.decodeFromString<List<NominatimHit>>(body) }
        .getOrDefault(emptyList())
        .mapNotNull { hit ->
            val lat = hit.lat.toDoubleOrNull() ?: return@mapNotNull null
            val lon = hit.lon.toDoubleOrNull() ?: return@mapNotNull null
            if (hit.displayName.isEmpty()) return@mapNotNull null
            GeocodedPlace(label = hit.displayName, point = MapPoint(lat, lon))
        }

/**
 * Place search returning MULTIPLE ranked suggestions: OpenStreetMap Nominatim
 * (free, key-less; the picker's debounce keeps request rates well inside its
 * usage policy, and we send an identifying User-Agent as it requires). The
 * platform [Geocoder] — which only ever returns a single best match — remains
 * the fallback when the network call fails. Deliberately NOT the Google Places
 * SDK: that would force API enablement + billing on every fork.
 */
fun platformPlaceGeocoder(context: Context): PlaceGeocoder {
    val client =
        OkHttpClient
            .Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    val appVersion =
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "dev"
    return PlaceGeocoder { query ->
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return@PlaceGeocoder emptyList()
        nominatimSearch(client, appVersion, trimmed).ifEmpty {
            platformGeocoderSearch(context, trimmed)
        }
    }
}

private suspend fun nominatimSearch(
    client: OkHttpClient,
    appVersion: String,
    query: String,
): List<GeocodedPlace> =
    withContext(Dispatchers.IO) {
        runCatching {
            val url = "$NOMINATIM_URL?q=${java.net.URLEncoder.encode(query, "UTF-8")}&format=json&limit=$MAX_RESULTS"
            val request =
                Request
                    .Builder()
                    .url(url)
                    .header("User-Agent", "ClearTravel/$appVersion (Android travel app)")
                    .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use emptyList()
                parseNominatimResponse(response.body?.string().orEmpty())
            }
        }.getOrDefault(emptyList())
    }

/** Single-best-match fallback via the platform [Geocoder] (offline-capable). */
private suspend fun platformGeocoderSearch(
    context: Context,
    query: String,
): List<GeocodedPlace> {
    if (!Geocoder.isPresent()) return emptyList()
    val geocoder = Geocoder(context, Locale.getDefault())
    return runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            suspendCancellableCoroutine { cont ->
                geocoder.getFromLocationName(query, MAX_RESULTS) { addresses ->
                    cont.resume(addresses.toList())
                }
            }
        } else {
            withContext(Dispatchers.IO) {
                @Suppress("DEPRECATION")
                geocoder.getFromLocationName(query, MAX_RESULTS).orEmpty()
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
