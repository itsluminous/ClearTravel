package com.itsluminous.cleartravel.feature.itinerary.intake

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject

/**
 * Expands a Google Maps SHORT link (`maps.app.goo.gl/…`) into the full URL that
 * carries the place name and coordinates (ADR-029 part D). Network is optional: a
 * failure yields null and the intake proceeds with "name unknown", never blocking.
 */
interface MapsLinkResolver {
    suspend fun resolve(shortUrl: String): String?
}

/**
 * One redirect hop at a time, redirects NOT auto-followed, so the `Location` header
 * is read directly; a few hops cover the goo.gl → google.com/maps chain. Short
 * timeouts keep an offline device from stalling the dialog for long.
 */
class HttpMapsLinkResolver
    @Inject
    constructor() : MapsLinkResolver {
        override suspend fun resolve(shortUrl: String): String? =
            withContext(Dispatchers.IO) {
                var current = shortUrl
                repeat(MAX_HOPS) {
                    val next = runCatching { locationHeader(current) }.getOrNull() ?: return@withContext current.takeIf { it != shortUrl }
                    current = next
                }
                current.takeIf { it != shortUrl }
            }

        private fun locationHeader(url: String): String? {
            val connection = (URL(url).openConnection() as HttpURLConnection)
            return try {
                connection.instanceFollowRedirects = false
                connection.requestMethod = "HEAD"
                connection.connectTimeout = TIMEOUT_MILLIS
                connection.readTimeout = TIMEOUT_MILLIS
                val code = connection.responseCode
                if (code in REDIRECT_CODES) {
                    connection.getHeaderField("Location")?.let { location ->
                        if (location.startsWith("http")) location else URL(URL(url), location).toString()
                    }
                } else {
                    null
                }
            } finally {
                connection.disconnect()
            }
        }

        private companion object {
            const val MAX_HOPS = 4
            const val TIMEOUT_MILLIS = 5_000
            val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        }
    }
