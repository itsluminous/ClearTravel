package com.itsluminous.cleartravel.core.data.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [BackgroundSyncStateStore] over the app's settings Preferences DataStore (the same
 * file `SettingsRepository` uses — one DataStore per file is a hard rule of the
 * library, so no second store is opened). Keys are namespaced `sync_*`.
 */
@Singleton
class DataStoreBackgroundSyncStateStore
    @Inject
    constructor(
        private val dataStore: DataStore<Preferences>,
    ) : BackgroundSyncStateStore {
        override suspend fun nextFlightDeparture(): Instant? = dataStore.data.first()[KEY_NEXT_FLIGHT_DEPARTURE]?.let(Instant::ofEpochMilli)

        override suspend fun setNextFlightDeparture(departure: Instant?) {
            dataStore.edit { prefs ->
                if (departure ==
                    null
                ) {
                    prefs.remove(KEY_NEXT_FLIGHT_DEPARTURE)
                } else {
                    prefs[KEY_NEXT_FLIGHT_DEPARTURE] = departure.toEpochMilli()
                }
            }
        }

        override val statuses: Flow<List<SyncWorkStatus>> =
            dataStore.data.map { prefs ->
                SyncWorkKind.entries.map { kind ->
                    SyncWorkStatus(
                        kind = kind,
                        lastCompletedAt = prefs[completedKey(kind)]?.let(Instant::ofEpochMilli),
                        lastDeferredAt = prefs[deferredKey(kind)]?.let(Instant::ofEpochMilli),
                        deferredSinceUnlock = prefs[deferredCountKey(kind)] ?: 0,
                    )
                }
            }

        override suspend fun recordDeferred(
            kind: SyncWorkKind,
            at: Instant,
        ) {
            dataStore.edit { prefs ->
                prefs[deferredKey(kind)] = at.toEpochMilli()
                prefs[deferredCountKey(kind)] = (prefs[deferredCountKey(kind)] ?: 0) + 1
            }
        }

        override suspend fun recordCompleted(
            kind: SyncWorkKind,
            at: Instant,
        ) {
            dataStore.edit { prefs -> prefs[completedKey(kind)] = at.toEpochMilli() }
        }

        override suspend fun resetDeferredCounts() {
            dataStore.edit { prefs -> SyncWorkKind.entries.forEach { prefs.remove(deferredCountKey(it)) } }
        }

        private companion object {
            val KEY_NEXT_FLIGHT_DEPARTURE = longPreferencesKey("sync_next_flight_departure")

            fun completedKey(kind: SyncWorkKind) = longPreferencesKey("sync_${kind.storageKey}_completed_at")

            fun deferredKey(kind: SyncWorkKind) = longPreferencesKey("sync_${kind.storageKey}_deferred_at")

            fun deferredCountKey(kind: SyncWorkKind) = intPreferencesKey("sync_${kind.storageKey}_deferred_count")
        }
    }
