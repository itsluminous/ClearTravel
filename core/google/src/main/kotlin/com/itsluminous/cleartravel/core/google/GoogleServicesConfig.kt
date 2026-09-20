package com.itsluminous.cleartravel.core.google

/**
 * Central access point for the Google OAuth web client id
 * ([BuildConfig.GOOGLE_WEB_CLIENT_ID], sourced from `local.properties`).
 *
 * When the id is blank the whole Google integration degrades gracefully into an
 * explained "not configured" state (spec feature 5: the app is fully functional
 * signed out) — no Google UI is launched and workers no-op. The id MUST be a
 * **Web application** OAuth client: an Android client id fails Credential Manager
 * linking with developer-console error `[28444]`. See docs/google-setup.md.
 */
object GoogleServicesConfig {
    val webClientId: String = BuildConfig.GOOGLE_WEB_CLIENT_ID

    val isConfigured: Boolean = webClientId.isNotBlank()
}

/**
 * OAuth scopes, requested INCREMENTALLY (spec feature 5): linking alone grants
 * nothing; each Settings toggle requests only the scope its feature needs.
 */
object GoogleScopes {
    /**
     * Creates secondary calendars and fully manages events on calendars the app
     * itself created — the least-privilege way to own a dedicated "ClearTravel"
     * calendar without ever touching the user's primary calendar.
     */
    const val CALENDAR_APP_CREATED = "https://www.googleapis.com/auth/calendar.app.created"

    /** Drive access limited to files the app itself created (uploads + backups). */
    const val DRIVE_FILE = "https://www.googleapis.com/auth/drive.file"

    /** The scopes the given feature toggles require (pure — unit-tested). */
    fun requiredFor(
        calendarSync: Boolean,
        driveUploads: Boolean,
        driveBackup: Boolean,
    ): List<String> =
        buildList {
            if (calendarSync) add(CALENDAR_APP_CREATED)
            if (driveUploads || driveBackup) add(DRIVE_FILE)
        }

    /** Scopes in [required] not yet covered by [granted] (pure — unit-tested). */
    fun missing(
        granted: Collection<String>,
        required: Collection<String>,
    ): List<String> = required.filterNot(granted::contains)
}
