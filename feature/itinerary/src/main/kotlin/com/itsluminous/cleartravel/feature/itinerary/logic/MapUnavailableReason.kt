package com.itsluminous.cleartravel.feature.itinerary.logic

/** Why the Google Map cannot render; null (from [mapUnavailableReason]) = renderable. */
enum class MapUnavailableReason {
    MISSING_PLAY_SERVICES,
    MISSING_API_KEY,
}

/**
 * Offline-first guard for the map composable (the ONLY network/Play-dependent UI):
 * decides whether the GoogleMap can render or the screen should degrade to an
 * inline notice while the timeline keeps working. Play services outranks the key —
 * without Play services the map cannot render regardless of key state.
 */
fun mapUnavailableReason(
    hasPlayServices: Boolean,
    hasApiKey: Boolean,
): MapUnavailableReason? =
    when {
        !hasPlayServices -> MapUnavailableReason.MISSING_PLAY_SERVICES
        !hasApiKey -> MapUnavailableReason.MISSING_API_KEY
        else -> null
    }
