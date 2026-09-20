package com.itsluminous.cleartravel.core.google.rest

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/** Minimal HTTP response view — enough for the Calendar/Drive v3 JSON APIs. */
data class GoogleApiResponse(
    val code: Int,
    val body: String,
) {
    val isSuccess: Boolean get() = code in 200..299
}

/** Signals a non-2xx Google API response; message carries the status + a body snippet. */
class GoogleApiException(
    val code: Int,
    body: String,
) : IOException("google api error $code: ${body.take(300)}")

/**
 * Tiny `HttpURLConnection`-based client for the Google REST v3 APIs — deliberately no
 * third-party HTTP/API-client dependency (the two JSON APIs need four verbs and a
 * multipart upload; a full client stack buys nothing but APK size). All calls run on
 * [Dispatchers.IO]; engines/tests use fake [com.itsluminous.cleartravel.core.google.calendar.CalendarClient]/
 * [com.itsluminous.cleartravel.core.google.drive.DriveClient] implementations and
 * never reach this class.
 */
@Singleton
class GoogleApiHttp
    @Inject
    constructor() {
        suspend fun request(
            method: String,
            url: String,
            accessToken: String,
            contentType: String? = null,
            body: ByteArray? = null,
        ): GoogleApiResponse =
            withContext(Dispatchers.IO) {
                val connection = URL(url).openConnection() as HttpURLConnection
                try {
                    // HttpURLConnection rejects PATCH as a request method; Google APIs
                    // accept the standard override header on a POST instead. PATCH (not
                    // PUT) matters for event updates: a full PUT clears user-set fields
                    // (reminders, colour) the body omits.
                    if (method == "PATCH") {
                        connection.requestMethod = "POST"
                        connection.setRequestProperty("X-HTTP-Method-Override", "PATCH")
                    } else {
                        connection.requestMethod = method
                    }
                    connection.connectTimeout = TIMEOUT_MS
                    connection.readTimeout = TIMEOUT_MS
                    connection.setRequestProperty("Authorization", "Bearer $accessToken")
                    if (contentType != null) connection.setRequestProperty("Content-Type", contentType)
                    if (body != null) {
                        connection.doOutput = true
                        connection.outputStream.use { it.write(body) }
                    }
                    val code = connection.responseCode
                    val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                    val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    GoogleApiResponse(code, text)
                } finally {
                    connection.disconnect()
                }
            }

        /**
         * GET [url] and stream the (binary) response body straight into [target] —
         * [request] buffers bodies as text, which would corrupt media bytes (Drive
         * `files.get?alt=media`). On a non-2xx response [target] is deleted and the
         * returned body carries the error text; on success the body is empty.
         */
        suspend fun downloadToFile(
            url: String,
            accessToken: String,
            target: File,
        ): GoogleApiResponse =
            withContext(Dispatchers.IO) {
                val connection = URL(url).openConnection() as HttpURLConnection
                try {
                    connection.requestMethod = "GET"
                    connection.connectTimeout = TIMEOUT_MS
                    connection.readTimeout = TIMEOUT_MS
                    connection.setRequestProperty("Authorization", "Bearer $accessToken")
                    val code = connection.responseCode
                    if (code in 200..299) {
                        connection.inputStream.use { input ->
                            target.outputStream().use { input.copyTo(it) }
                        }
                        GoogleApiResponse(code, "")
                    } else {
                        target.delete()
                        GoogleApiResponse(
                            code,
                            connection.errorStream
                                ?.bufferedReader()
                                ?.use { it.readText() }
                                .orEmpty(),
                        )
                    }
                } finally {
                    connection.disconnect()
                }
            }

        private companion object {
            const val TIMEOUT_MS = 30_000
        }
    }
