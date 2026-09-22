package com.itsluminous.cleartravel.feature.trains.share

import com.itsluminous.cleartravel.feature.trains.isValidPnr
import java.net.URI

/**
 * The PNR share-link format (ADR-020): one pure place that both BUILDS the link put
 * into share text and PARSES an incoming link back into a PNR.
 *
 * Two URI shapes are accepted, both declared as `ACTION_VIEW` intent filters on the
 * app's `MainActivity`:
 * - `https://cleartravel.itsluminous.com/pnr/<pnr>` — the link we share (a
 *   real web URL, so recipients without the app still get a page to land on).
 * - `cleartravel://pnr/<pnr>` — custom scheme, belt-and-braces for launchers that
 *   mangle https deep links.
 */
object TicketShareLinks {
    const val HTTPS_SCHEME = "https"
    const val HTTPS_HOST = "cleartravel.itsluminous.com"
    const val HTTPS_PATH_PREFIX = "/pnr/"
    const val CUSTOM_SCHEME = "cleartravel"
    const val CUSTOM_HOST = "pnr"

    /** The canonical https share link for [pnr]. */
    fun shareUrl(pnr: String): String = "$HTTPS_SCHEME://$HTTPS_HOST$HTTPS_PATH_PREFIX${pnr.trim()}"

    /**
     * Share-sheet caption. [template] is the localized
     * `trains_share_text` resource (`%1$s` = PNR, `%2$s` = link) — passed in so this
     * stays pure and the string stays in resources (hard rule 1).
     */
    fun buildShareText(
        pnr: String,
        template: String,
    ): String = String.format(template, pnr.trim(), shareUrl(pnr))

    /**
     * Extracts a valid 10-digit PNR from an incoming deep link in either shape, or
     * null for anything else (other hosts, malformed PNRs, unrelated paths).
     */
    fun parsePnr(link: String?): String? {
        if (link.isNullOrBlank()) return null
        val uri = runCatching { URI(link.trim()) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host?.lowercase() ?: return null
        val path = uri.path.orEmpty()
        val candidate =
            when {
                scheme == HTTPS_SCHEME && host == HTTPS_HOST && path.startsWith(HTTPS_PATH_PREFIX) ->
                    path.removePrefix(HTTPS_PATH_PREFIX)
                scheme == CUSTOM_SCHEME && host == CUSTOM_HOST -> path.removePrefix("/")
                else -> return null
            }.trimEnd('/')
        return candidate.takeIf(::isValidPnr)?.trim()
    }
}
