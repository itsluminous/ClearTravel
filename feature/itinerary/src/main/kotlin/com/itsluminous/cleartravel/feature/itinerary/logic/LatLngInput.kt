package com.itsluminous.cleartravel.feature.itinerary.logic

import java.util.Locale

/**
 * Parses a manual "lat, lng" entry. Accepts two comma-separated decimals within
 * valid coordinate ranges (lat ±90, lng ±180); anything else returns null.
 */
fun parseLatLng(text: String): MapPoint? {
    val parts = text.split(',')
    if (parts.size != 2) return null
    val lat = parts[0].trim().toDoubleOrNull() ?: return null
    val lng = parts[1].trim().toDoubleOrNull() ?: return null
    if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return null
    return MapPoint(lat, lng)
}

/** Formats coordinates for display/editing — 5 decimals, locale-stable. */
fun formatLatLng(
    latitude: Double,
    longitude: Double,
): String = String.format(Locale.US, "%.5f, %.5f", latitude, longitude)
