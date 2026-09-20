package com.itsluminous.cleartravel.core.google.auth

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Everything persisted about the Google link (device-local, never in Room/backup). */
data class GoogleLinkSnapshot(
    /** Linked account email; null = not linked. */
    val email: String? = null,
    /** OAuth scopes the user has granted so far (incremental, grows per toggle). */
    val grantedScopes: Set<String> = emptySet(),
    /** Id of the app-created "ClearTravel" calendar; null = not created yet. */
    val calendarId: String? = null,
    /** Id of the app-created "ClearTravel" Drive folder; null = not created yet. */
    val driveFolderId: String? = null,
    val calendarSyncEnabled: Boolean = false,
    val driveUploadsEnabled: Boolean = false,
    val driveBackupEnabled: Boolean = false,
) {
    val isLinked: Boolean get() = email != null
}

/**
 * Persistence of the Google link + feature toggles. Deliberately DataStore-backed and
 * device-local (like ADR-013's poll state): the link is per-device by nature and must
 * never enter the backup/merge surface. Behind an interface so engines and ViewModels
 * are tested against an in-memory fake.
 */
interface GoogleLinkStore {
    val snapshot: Flow<GoogleLinkSnapshot>

    suspend fun current(): GoogleLinkSnapshot

    /** Records a fresh link. Cached calendar/folder ids survive re-links of the SAME account. */
    suspend fun setLinked(
        email: String,
        grantedScopes: Set<String>,
    )

    /** Adds newly granted scopes to the persisted set. */
    suspend fun addGrantedScopes(scopes: Collection<String>)

    suspend fun setCalendarId(id: String?)

    suspend fun setDriveFolderId(id: String?)

    suspend fun setCalendarSyncEnabled(enabled: Boolean)

    suspend fun setDriveUploadsEnabled(enabled: Boolean)

    suspend fun setDriveBackupEnabled(enabled: Boolean)

    /** Clears the whole link (disconnect): account, scopes, cached ids, toggles. */
    suspend fun clear()
}

private val Context.googleLinkDataStore: DataStore<Preferences> by preferencesDataStore(name = "google_link")

/** [GoogleLinkStore] over a Preferences DataStore (`google_link`). */
@Singleton
class DataStoreGoogleLinkStore
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : GoogleLinkStore {
        private val dataStore get() = context.googleLinkDataStore

        override val snapshot: Flow<GoogleLinkSnapshot> = dataStore.data.map(::toSnapshot)

        override suspend fun current(): GoogleLinkSnapshot = snapshot.first()

        override suspend fun setLinked(
            email: String,
            grantedScopes: Set<String>,
        ) {
            dataStore.edit { prefs ->
                val sameAccount = prefs[KEY_EMAIL] == email
                prefs[KEY_EMAIL] = email
                prefs[KEY_SCOPES] = grantedScopes
                if (!sameAccount) {
                    // Cached ids belong to the previous account — never reuse them.
                    prefs.remove(KEY_CALENDAR_ID)
                    prefs.remove(KEY_DRIVE_FOLDER_ID)
                }
            }
        }

        override suspend fun addGrantedScopes(scopes: Collection<String>) {
            dataStore.edit { prefs ->
                prefs[KEY_SCOPES] = (prefs[KEY_SCOPES] ?: emptySet()) + scopes
            }
        }

        override suspend fun setCalendarId(id: String?) {
            dataStore.edit { prefs ->
                if (id == null) prefs.remove(KEY_CALENDAR_ID) else prefs[KEY_CALENDAR_ID] = id
            }
        }

        override suspend fun setDriveFolderId(id: String?) {
            dataStore.edit { prefs ->
                if (id == null) prefs.remove(KEY_DRIVE_FOLDER_ID) else prefs[KEY_DRIVE_FOLDER_ID] = id
            }
        }

        override suspend fun setCalendarSyncEnabled(enabled: Boolean) {
            dataStore.edit { it[KEY_CALENDAR_SYNC] = enabled }
        }

        override suspend fun setDriveUploadsEnabled(enabled: Boolean) {
            dataStore.edit { it[KEY_DRIVE_UPLOADS] = enabled }
        }

        override suspend fun setDriveBackupEnabled(enabled: Boolean) {
            dataStore.edit { it[KEY_DRIVE_BACKUP] = enabled }
        }

        override suspend fun clear() {
            dataStore.edit { it.clear() }
        }

        private fun toSnapshot(prefs: Preferences): GoogleLinkSnapshot =
            GoogleLinkSnapshot(
                email = prefs[KEY_EMAIL],
                grantedScopes = prefs[KEY_SCOPES] ?: emptySet(),
                calendarId = prefs[KEY_CALENDAR_ID],
                driveFolderId = prefs[KEY_DRIVE_FOLDER_ID],
                calendarSyncEnabled = prefs[KEY_CALENDAR_SYNC] ?: false,
                driveUploadsEnabled = prefs[KEY_DRIVE_UPLOADS] ?: false,
                driveBackupEnabled = prefs[KEY_DRIVE_BACKUP] ?: false,
            )

        private companion object {
            val KEY_EMAIL = stringPreferencesKey("email")
            val KEY_SCOPES = stringSetPreferencesKey("granted_scopes")
            val KEY_CALENDAR_ID = stringPreferencesKey("calendar_id")
            val KEY_DRIVE_FOLDER_ID = stringPreferencesKey("drive_folder_id")
            val KEY_CALENDAR_SYNC = booleanPreferencesKey("calendar_sync_enabled")
            val KEY_DRIVE_UPLOADS = booleanPreferencesKey("drive_uploads_enabled")
            val KEY_DRIVE_BACKUP = booleanPreferencesKey("drive_backup_enabled")
        }
    }
