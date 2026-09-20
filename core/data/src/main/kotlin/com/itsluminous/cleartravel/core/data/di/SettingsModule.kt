package com.itsluminous.cleartravel.core.data.di

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton

/** Marks the encrypted [SharedPreferences] holding user-entered API keys (ADR-007). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SecurePreferences

/**
 * Provides the settings Preferences DataStore (theme, provider selection) and the
 * EncryptedSharedPreferences instance for secrets (ADR-007: androidx-security-crypto
 * with an Android Keystore master key — keys never sit on disk in plaintext).
 */
@Module
@InstallIn(SingletonComponent::class)
object SettingsModule {
    private const val SETTINGS_STORE_NAME = "settings"
    private const val SECURE_PREFS_FILE = "secure_settings"

    @Provides
    @Singleton
    fun provideSettingsDataStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            produceFile = { context.preferencesDataStoreFile(SETTINGS_STORE_NAME) },
        )

    @Provides
    @Singleton
    @SecurePreferences
    fun provideSecurePreferences(
        @ApplicationContext context: Context,
    ): SharedPreferences {
        val masterKey =
            MasterKey
                .Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
        return EncryptedSharedPreferences.create(
            context,
            SECURE_PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }
}
