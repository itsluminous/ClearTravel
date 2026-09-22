package com.itsluminous.cleartravel.ui.intake

import com.itsluminous.cleartravel.feature.itinerary.logic.MapsLinks

/** Where a shared `text/plain` goes (ADR-029 part D disambiguation of the one share filter). */
sealed interface SharedTextRoute {
    /** A Google Maps link → the "Add place from Google Maps" intake. */
    data class MapsLink(
        val text: String,
    ) : SharedTextRoute

    /** Anything else keeps the original behaviour: IRCTC SMS/email text → train ticket form. */
    data class TrainText(
        val text: String,
    ) : SharedTextRoute
}

/**
 * Pure classifier for shared text. A Maps URL anywhere in the text wins (share
 * sheets wrap the link in a sentence); blank text routes nowhere.
 */
fun routeSharedText(text: String?): SharedTextRoute? {
    val trimmed = text?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    return if (MapsLinks.isMapsLink(trimmed)) SharedTextRoute.MapsLink(trimmed) else SharedTextRoute.TrainText(trimmed)
}
