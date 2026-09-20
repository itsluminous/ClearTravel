package com.itsluminous.cleartravel.core.data.repository.offline

import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.itsluminous.cleartravel.core.data.di.SecurePreferences
import com.itsluminous.cleartravel.core.data.repository.SettingsRepository
import com.itsluminous.cleartravel.core.model.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [SettingsRepository] over a Preferences DataStore (theme, provider selection) and
 * EncryptedSharedPreferences for user-entered API keys (ADR-007). The secure store is
 * injected as a plain [SharedPreferences] behind the [SecurePreferences] qualifier so
 * tests can substitute an unencrypted instance (Robolectric has no Android Keystore).
 */
@Singleton
class DefaultSettingsRepository
    @Inject
    constructor(
        private val dataStore: DataStore<Preferences>,
        @SecurePreferences private val securePreferences: SharedPreferences,
    ) : SettingsRepository {
        override val themeMode: Flow<ThemeMode> = dataStore.data.map { ThemeMode.fromStorage(it[KEY_THEME_MODE]) }

        override suspend fun setThemeMode(mode: ThemeMode) {
            dataStore.edit { it[KEY_THEME_MODE] = mode.storageValue }
        }

        override val trainProviderId: Flow<String?> = dataStore.data.map { it[KEY_TRAIN_PROVIDER_ID] }

        override suspend fun setTrainProviderId(providerId: String?) {
            dataStore.edit { prefs ->
                if (providerId == null) prefs.remove(KEY_TRAIN_PROVIDER_ID) else prefs[KEY_TRAIN_PROVIDER_ID] = providerId
            }
        }

        override val flightProviderId: Flow<String?> = dataStore.data.map { it[KEY_FLIGHT_PROVIDER_ID] }

        override suspend fun setFlightProviderId(providerId: String?) {
            dataStore.edit { prefs ->
                if (providerId == null) prefs.remove(KEY_FLIGHT_PROVIDER_ID) else prefs[KEY_FLIGHT_PROVIDER_ID] = providerId
            }
        }

        override suspend fun trainApiKey(): String? = readSecure(SECURE_KEY_TRAIN_API)

        override suspend fun setTrainApiKey(key: String?) = writeSecure(SECURE_KEY_TRAIN_API, key)

        override suspend fun flightApiKey(): String? = readSecure(SECURE_KEY_FLIGHT_API)

        override suspend fun setFlightApiKey(key: String?) = writeSecure(SECURE_KEY_FLIGHT_API, key)

        private suspend fun readSecure(name: String): String? = withContext(Dispatchers.IO) { securePreferences.getString(name, null) }

        private suspend fun writeSecure(
            name: String,
            value: String?,
        ) = withContext(Dispatchers.IO) {
            securePreferences.edit(commit = true) {
                if (value == null) remove(name) else putString(name, value)
            }
        }

        companion object {
            private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
            private val KEY_TRAIN_PROVIDER_ID = stringPreferencesKey("train_provider_id")
            private val KEY_FLIGHT_PROVIDER_ID = stringPreferencesKey("flight_provider_id")
            private const val SECURE_KEY_TRAIN_API = "train_api_key"
            private const val SECURE_KEY_FLIGHT_API = "flight_api_key"
        }
    }
