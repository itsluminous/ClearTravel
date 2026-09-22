package com.itsluminous.cleartravel.core.security.di

import android.content.Context
import com.itsluminous.cleartravel.core.security.biometric.BiometricKeyWrapper
import com.itsluminous.cleartravel.core.security.biometric.KeystoreBiometricKeyWrapper
import com.itsluminous.cleartravel.core.security.file.LocalFileCipher
import com.itsluminous.cleartravel.core.security.lock.AppLockController
import com.itsluminous.cleartravel.core.security.vault.DefaultKeyVault
import com.itsluminous.cleartravel.core.security.vault.FileKeyFileStore
import com.itsluminous.cleartravel.core.security.vault.KeyVault
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt wiring of `core:security` (ADR-031). The e2e suite replaces this module with
 * an in-memory, pre-unlocked vault so the existing hermetic tests run unchanged.
 */
@Module
@InstallIn(SingletonComponent::class)
object SecurityModule {
    @Provides
    @Singleton
    fun provideKeyVault(
        @ApplicationContext context: Context,
    ): KeyVault = DefaultKeyVault(FileKeyFileStore.default(context.filesDir))

    @Provides
    @Singleton
    fun provideLocalFileCipher(vault: KeyVault): LocalFileCipher = LocalFileCipher(key = { vault.fileKey() })

    @Provides
    @Singleton
    fun provideBiometricKeyWrapper(): BiometricKeyWrapper = KeystoreBiometricKeyWrapper()

    @Provides
    @Singleton
    fun provideAppLockController(): AppLockController = AppLockController()
}
