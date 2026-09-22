package com.itsluminous.cleartravel.core.data.di

import android.content.Context
import com.itsluminous.cleartravel.core.data.security.StorageEncryptionMigrator
import com.itsluminous.cleartravel.core.security.file.LocalFileCipher
import com.itsluminous.cleartravel.core.security.vault.KeyVault
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * ADR-031 storage-security wiring that is NOT part of [DatabaseModule] (which the e2e
 * suite swaps for an in-memory database): the one-time file migration over the real
 * `filesDir` layout.
 */
@Module
@InstallIn(SingletonComponent::class)
object StorageSecurityModule {
    @Provides
    @Singleton
    fun provideStorageEncryptionMigrator(
        @ApplicationContext context: Context,
        vault: KeyVault,
        fileCipher: LocalFileCipher,
    ): StorageEncryptionMigrator = StorageEncryptionMigrator(context.filesDir, vault, fileCipher)
}
