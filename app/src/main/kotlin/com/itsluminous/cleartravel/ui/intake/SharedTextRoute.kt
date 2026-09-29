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

    /**
     * Anything else is a journey SMS/email → the "What's this text?" intake, which
     * preselects train or flight (ADR-042) and routes to that feature's form.
     */
    data class JourneyText(
        val text: String,
    ) : SharedTextRoute
}

/**
 * Pure router for shared text. A Clear Travel share link anywhere in the text wins
 * (ADR-039), then a Maps URL (share sheets wrap links in a sentence), else the text
 * is a journey message for the intake (ADR-042); blank text routes nowhere.
 */
fun routeSharedText(text: String?): SharedTextRoute? {
    val trimmed = text?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    ShareLinkCodec.findInText(trimmed)?.let { return SharedTextRoute.ShareLink(ShareLinkCodec.httpsUrl(it.kind, it.blob)) }
    return if (MapsLinks.isMapsLink(trimmed)) SharedTextRoute.MapsLink(trimmed) else SharedTextRoute.JourneyText(trimmed)
}
