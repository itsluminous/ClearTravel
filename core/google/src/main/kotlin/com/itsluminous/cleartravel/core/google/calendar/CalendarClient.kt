package com.itsluminous.cleartravel.core.google.calendar

import com.itsluminous.cleartravel.core.google.auth.GoogleAccessTokenProvider
import com.itsluminous.cleartravel.core.google.auth.GoogleNotAvailableException
import com.itsluminous.cleartravel.core.google.rest.GoogleApiException
import com.itsluminous.cleartravel.core.google.rest.GoogleApiHttp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.net.URLEncoder
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A calendar event payload, ready for the Calendar v3 REST body. Either the all-day
 * pair ([startDate]/[endDateExclusive]) or the timed pair
 * ([startDateTime]/[endDateTime]) is set, never both.
 */
data class CalendarEvent(
    val summary: String,
    val description: String = "",
    val location: String = "",
    val startDate: LocalDate? = null,
    /** Calendar v3 all-day `end.date` is EXCLUSIVE — always the last day + 1. */
    val endDateExclusive: LocalDate? = null,
    val startDateTime: LocalDateTime? = null,
    val endDateTime: LocalDateTime? = null,
    val timeZone: String? = null,
    /** Private extended properties: the source row id + an app marker. */
    val privateProperties: Map<String, String> = emptyMap(),
) {
    val isAllDay: Boolean get() = startDate != null

    /**
     * Stable content hash of everything pushed — the sync engine skips the HTTP
     * update when the fingerprint of a row's mapped event is unchanged.
     */
    fun fingerprint(): String =
        listOf(summary, description, location, startDate, endDateExclusive, startDateTime, endDateTime, timeZone)
            .joinToString("|")
            .hashCode()
            .toString(16)
}

/**
 * Low-level Calendar v3 operations, behind an interface so [CalendarSyncEngine] is
 * fully testable with a fake — tests never touch the live API.
 */
interface CalendarClient {
    /** The calendar's display name, or null when it no longer exists / is inaccessible. */
    suspend fun calendarSummary(calendarId: String): String?

    /** Creates a secondary calendar named [summary] and returns its id. */
    suspend fun createCalendar(summary: String): String

    /** Deletes an app-created calendar; already-gone is not an error. */
    suspend fun deleteCalendar(calendarId: String)

    /** Inserts [event] into [calendarId] and returns the created event id. */
    suspend fun insertEvent(
        calendarId: String,
        event: CalendarEvent,
    ): String

    /** PATCHes the app-owned fields of [eventId]. */
    suspend fun updateEvent(
        calendarId: String,
        eventId: String,
        event: CalendarEvent,
    )

    /** Deleting an already-gone event is NOT an error (idempotent cleanup). */
    suspend fun deleteEvent(
        calendarId: String,
        eventId: String,
    )
}

private const val BASE_URL = "https://www.googleapis.com/calendar/v3"

/** [CalendarClient] over the Calendar v3 REST endpoints. */
@Singleton
class RestCalendarClient
    @Inject
    constructor(
        private val http: GoogleApiHttp,
        private val tokenProvider: GoogleAccessTokenProvider,
    ) : CalendarClient {
        private val json = Json { ignoreUnknownKeys = true }

        private suspend fun token(): String = tokenProvider.accessToken() ?: throw GoogleNotAvailableException()

        override suspend fun calendarSummary(calendarId: String): String? {
            val response = http.request("GET", "$BASE_URL/calendars/${encode(calendarId)}", token())
            if (response.code == 404 || response.code == 410 || response.code == 403) return null
            if (!response.isSuccess) throw GoogleApiException(response.code, response.body)
            return json
                .parseToJsonElement(response.body)
                .jsonObject["summary"]
                ?.jsonPrimitive
                ?.content
                .orEmpty()
        }

        override suspend fun createCalendar(summary: String): String {
            val body = buildJsonObject { put("summary", summary) }.toString()
            val response =
                http.request(
                    "POST",
                    "$BASE_URL/calendars",
                    token(),
                    contentType = JSON_CONTENT_TYPE,
                    body = body.toByteArray(),
                )
            if (!response.isSuccess) throw GoogleApiException(response.code, response.body)
            return json
                .parseToJsonElement(response.body)
                .jsonObject
                .getValue("id")
                .jsonPrimitive.content
        }

        override suspend fun deleteCalendar(calendarId: String) {
            val response = http.request("DELETE", "$BASE_URL/calendars/${encode(calendarId)}", token())
            if (!response.isSuccess && response.code != 404 && response.code != 410) {
                throw GoogleApiException(response.code, response.body)
            }
        }

        override suspend fun insertEvent(
            calendarId: String,
            event: CalendarEvent,
        ): String {
            val response =
                http.request(
                    "POST",
                    "$BASE_URL/calendars/${encode(calendarId)}/events",
                    token(),
                    contentType = JSON_CONTENT_TYPE,
                    body = event.toRequestBody().toByteArray(),
                )
            if (!response.isSuccess) throw GoogleApiException(response.code, response.body)
            return json
                .parseToJsonElement(response.body)
                .jsonObject
                .getValue("id")
                .jsonPrimitive.content
        }

        override suspend fun updateEvent(
            calendarId: String,
            eventId: String,
            event: CalendarEvent,
        ) {
            val response =
                http.request(
                    "PATCH",
                    "$BASE_URL/calendars/${encode(calendarId)}/events/${encode(eventId)}",
                    token(),
                    contentType = JSON_CONTENT_TYPE,
                    body = event.toRequestBody().toByteArray(),
                )
            if (!response.isSuccess) throw GoogleApiException(response.code, response.body)
        }

        override suspend fun deleteEvent(
            calendarId: String,
            eventId: String,
        ) {
            val response =
                http.request("DELETE", "$BASE_URL/calendars/${encode(calendarId)}/events/${encode(eventId)}", token())
            if (!response.isSuccess && response.code != 404 && response.code != 410) {
                throw GoogleApiException(response.code, response.body)
            }
        }

        private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

        private companion object {
            const val JSON_CONTENT_TYPE = "application/json; charset=UTF-8"
        }
    }

/** Calendar v3 event resource body. */
internal fun CalendarEvent.toRequestBody(): String =
    buildJsonObject {
        put("summary", summary)
        put("description", description)
        put("location", location)
        // The unused date variant is EXPLICITLY nulled: a PATCH only clears fields the
        // body names, so a row switching timed ↔ all-day must null the other pair or
        // the event keeps both and the API rejects it. Inserts ignore the nulls.
        if (isAllDay) {
            putJsonObject("start") {
                put("date", startDate.toString())
                put("dateTime", JsonNull)
                put("timeZone", JsonNull)
            }
            putJsonObject("end") {
                put("date", endDateExclusive.toString())
                put("dateTime", JsonNull)
                put("timeZone", JsonNull)
            }
        } else {
            putJsonObject("start") {
                put("date", JsonNull)
                put("dateTime", startDateTime!!.toRfc3339Local())
                put("timeZone", timeZone)
            }
            putJsonObject("end") {
                put("date", JsonNull)
                put("dateTime", endDateTime!!.toRfc3339Local())
                put("timeZone", timeZone)
            }
        }
        if (privateProperties.isNotEmpty()) {
            putJsonObject("extendedProperties") {
                putJsonObject("private") {
                    for ((key, value) in privateProperties) put(key, value)
                }
            }
        }
    }.toString()

/**
 * Calendar v3 `dateTime` values must be RFC3339, which makes SECONDS mandatory —
 * `LocalDateTime.toString()` omits `:00` seconds and Google rejects the truncated
 * form with a bare HTTP 400. The zone offset is intentionally absent: the body
 * carries an explicit `timeZone` field.
 */
private val RFC3339_LOCAL: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

internal fun LocalDateTime.toRfc3339Local(): String = format(RFC3339_LOCAL)
