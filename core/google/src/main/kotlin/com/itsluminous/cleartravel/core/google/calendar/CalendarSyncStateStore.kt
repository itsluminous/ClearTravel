package com.itsluminous.cleartravel.core.google.calendar

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** What the engine remembers about one pushed event. */
data class SyncedEventRecord(
    val eventId: String,
    /** [CalendarEvent.fingerprint] at last push — unchanged content skips the HTTP call. */
    val fingerprint: String,
)

/**
 * Device-local bookkeeping of pushed events (rowId → event id + content fingerprint).
 * Deliberately OUTSIDE Room (like ADR-013's poll state): it must never enter the
 * backup/merge surface — a restored device re-adopts events from the rows'
 * `googleEventId` columns instead. This map is also what makes DELETIONS detectable
 * without tombstone queries: a row id present here but absent from the live set was
 * deleted (or its trip was), so its event is removed.
 */
interface CalendarSyncStateStore {
    suspend fun all(): Map<String, SyncedEventRecord>

    suspend fun put(
        rowId: String,
        record: SyncedEventRecord,
    )

    suspend fun remove(rowId: String)

    suspend fun clear()
}

/** [CalendarSyncStateStore] over a private SharedPreferences file. */
@Singleton
class PreferencesCalendarSyncStateStore
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : CalendarSyncStateStore {
        private val prefs: SharedPreferences by lazy {
            context.getSharedPreferences("google_calendar_sync_state", Context.MODE_PRIVATE)
        }

        override suspend fun all(): Map<String, SyncedEventRecord> =
            withContext(Dispatchers.IO) {
                prefs.all
                    .mapNotNull { (key, value) ->
                        val parts = (value as? String)?.split(SEPARATOR, limit = 2) ?: return@mapNotNull null
                        if (parts.size != 2) return@mapNotNull null
                        key to SyncedEventRecord(eventId = parts[0], fingerprint = parts[1])
                    }.toMap()
            }

        override suspend fun put(
            rowId: String,
            record: SyncedEventRecord,
        ) {
            withContext(Dispatchers.IO) {
                prefs.edit().putString(rowId, "${record.eventId}$SEPARATOR${record.fingerprint}").apply()
            }
        }

        override suspend fun remove(rowId: String) {
            withContext(Dispatchers.IO) {
                prefs.edit().remove(rowId).apply()
            }
        }

        override suspend fun clear() {
            withContext(Dispatchers.IO) {
                prefs.edit().clear().apply()
            }
        }

        private companion object {
            const val SEPARATOR = "|"
        }
    }
