package com.itsluminous.cleartravel.di

import com.itsluminous.cleartravel.core.data.di.SettingsModule
import dagger.Module
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn

/**
 * Replaces [SettingsModule] for the e2e suite with NO bindings. Hilt creates one
 * SingletonComponent per TEST CLASS in the same instrumentation process; the production
 * module would construct a second Preferences DataStore over the same
 * `settings.preferences_pb` file and crash the process ("There are multiple DataStores
 * active for the same file") as soon as a second test class runs. The DataStore/
 * EncryptedSharedPreferences bindings are only consumed by `DefaultSettingsRepository`,
 * which [TestRepositoryModule] swaps for an in-memory fake — so nothing here is needed
 * (and the datastore artifact is not on the androidTest compile classpath anyway).
 */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [SettingsModule::class])
object TestSettingsModule
