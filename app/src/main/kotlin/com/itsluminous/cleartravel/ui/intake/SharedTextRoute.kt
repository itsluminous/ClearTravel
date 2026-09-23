package com.itsluminous.cleartravel.ui.intake

import com.itsluminous.cleartravel.core.data.share.ShareLinkCodec
import com.itsluminous.cleartravel.feature.itinerary.logic.MapsLinks

/** Where a shared `text/plain` goes (ADR-029 part D disambiguation of the one share filter). */
sealed interface SharedTextRoute {
    /** A Clear Travel share link (ADR-039) pasted/forwarded as text → the same path as tapping it. */
    data class ShareLink(
        val link: String,
    ) : SharedTextRoute

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
 * Pure classifier for shared text. A Clear Travel share link anywhere in the text
 * wins (ADR-039), then a Maps URL (share sheets wrap links in a sentence); blank
 * text routes nowhere.
 */
fun routeSharedText(text: String?): SharedTextRoute? {
    val trimmed = text?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    ShareLinkCodec.findInText(trimmed)?.let { return SharedTextRoute.ShareLink(ShareLinkCodec.httpsUrl(it.kind, it.blob)) }
    return if (MapsLinks.isMapsLink(trimmed)) SharedTextRoute.MapsLink(trimmed) else SharedTextRoute.TrainText(trimmed)
}
