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

        override suspend fun trainDepartureHints(): List<TrainDepartureHint> =
            dataStore.data
                .first()[KEY_TRAIN_DEPARTURES]
                .orEmpty()
                .mapNotNull(::decodeTrainHint)

        override suspend fun setTrainDepartureHints(hints: Collection<TrainDepartureHint>) {
            dataStore.edit { prefs ->
                if (hints.isEmpty()) {
                    prefs.remove(KEY_TRAIN_DEPARTURES)
                } else {
                    prefs[KEY_TRAIN_DEPARTURES] = hints.map(::encodeTrainHint).toSet()
                }
            }
        }

        override suspend fun remindedTrainKeys(): Set<String> = dataStore.data.first()[KEY_TRAIN_REMINDED].orEmpty()

        override suspend fun addRemindedTrainKey(key: String) {
            dataStore.edit { prefs -> prefs[KEY_TRAIN_REMINDED] = prefs[KEY_TRAIN_REMINDED].orEmpty() + key }
        }

        override suspend fun retainRemindedTrainKeys(keys: Collection<String>) {
            dataStore.edit { prefs ->
                val kept = prefs[KEY_TRAIN_REMINDED].orEmpty().intersect(keys.toSet())
                if (kept.isEmpty()) prefs.remove(KEY_TRAIN_REMINDED) else prefs[KEY_TRAIN_REMINDED] = kept
            }
        }

        override val statuses: Flow<List<SyncWorkStatus>> =
            dataStore.data.map { prefs ->
                SyncWorkKind.entries.map { kind ->
                    SyncWorkStatus(
                        kind = kind,
                        lastCompletedAt = prefs[completedKey(kind)]?.let(Instant::ofEpochMilli),
                        lastDeferredAt = prefs[deferredKey(kind)]?.let(Instant::ofEpochMilli),
                        deferredSinceUnlock = prefs[deferredShownKey(kind)] ?: 0,
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
            dataStore.edit { prefs ->
                SyncWorkKind.entries.forEach { kind ->
                    prefs[deferredShownKey(kind)] = prefs[deferredCountKey(kind)] ?: 0
                    prefs.remove(deferredCountKey(kind))
                }
            }
        }

        private companion object {
            val KEY_FLIGHT_DEPARTURES = stringSetPreferencesKey("sync_flight_departures")

            /** ADR-044: `<epochMillis>:<sha256(pnr)>` per active ticket. */
            val KEY_TRAIN_DEPARTURES = stringSetPreferencesKey("sync_train_departures")

            /** ADR-044: opaque reminder keys (hash + lead) already posted. */
            val KEY_TRAIN_REMINDED = stringSetPreferencesKey("sync_train_reminded")
            const val TRAIN_HINT_SEPARATOR = ':'

            fun encodeTrainHint(hint: TrainDepartureHint): String = "${hint.departure.toEpochMilli()}$TRAIN_HINT_SEPARATOR${hint.pnrHash}"

            fun decodeTrainHint(raw: String): TrainDepartureHint? {
                val at = raw.indexOf(TRAIN_HINT_SEPARATOR)
                if (at <= 0 || at == raw.lastIndex) return null
                val millis = raw.substring(0, at).toLongOrNull() ?: return null
                return TrainDepartureHint(Instant.ofEpochMilli(millis), raw.substring(at + 1))
            }

            fun completedKey(kind: SyncWorkKind) = longPreferencesKey("sync_${kind.storageKey}_completed_at")

            fun deferredKey(kind: SyncWorkKind) = longPreferencesKey("sync_${kind.storageKey}_deferred_at")

            /** Running count of the current locked period. */
            fun deferredCountKey(kind: SyncWorkKind) = intPreferencesKey("sync_${kind.storageKey}_deferred_count")

            /** Count of the locked period that ended at the last unlock — what Settings shows. */
            fun deferredShownKey(kind: SyncWorkKind) = intPreferencesKey("sync_${kind.storageKey}_deferred_shown")
        }
    }
