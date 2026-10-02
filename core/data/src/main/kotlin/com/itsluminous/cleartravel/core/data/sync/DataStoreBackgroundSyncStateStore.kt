package com.itsluminous.cleartravel.core.data.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
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
        override suspend fun flightDepartureHints(): List<Instant> =
            dataStore.data
                .first()[KEY_FLIGHT_DEPARTURES]
                .orEmpty()
                .mapNotNull { it.toLongOrNull()?.let(Instant::ofEpochMilli) }

        override suspend fun setFlightDepartureHints(departures: Collection<Instant>) {
            dataStore.edit { prefs ->
                if (departures.isEmpty()) {
                    prefs.remove(KEY_FLIGHT_DEPARTURES)
                } else {
                    prefs[KEY_FLIGHT_DEPARTURES] = departures.map { it.toEpochMilli().toString() }.toSet()
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
            val KEY_FLIGHT_DEPARTURES = stringSetPreferencesKey("sync_flight_departures")

            fun completedKey(kind: SyncWorkKind) = longPreferencesKey("sync_${kind.storageKey}_completed_at")

            fun deferredKey(kind: SyncWorkKind) = longPreferencesKey("sync_${kind.storageKey}_deferred_at")

            fun deferredCountKey(kind: SyncWorkKind) = intPreferencesKey("sync_${kind.storageKey}_deferred_count")
        }
    }
