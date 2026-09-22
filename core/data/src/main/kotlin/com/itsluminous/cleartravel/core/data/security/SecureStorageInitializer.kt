package com.itsluminous.cleartravel.core.data.security

import com.itsluminous.cleartravel.core.database.ClearTravelDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * The work that must finish between a successful unlock and revealing the UI
 * (ADR-031). Failures propagate so the unlock screen can show "could not secure your
 * data" and offer a retry rather than continuing with a half-migrated store.
 */
interface SecureStorageInitializer {
    suspend fun prepare(): StorageMigrationReport
}

/**
 * Opens the database (which converts a plaintext database in place on the first
 * post-update open) and runs the one-time file migration. Idempotent and cheap once
 * both migrations are done — one open and a flag read.
 */
@Singleton
class DefaultSecureStorageInitializer
    @Inject
    constructor(
        private val database: Provider<ClearTravelDatabase>,
        private val storageMigrator: StorageEncryptionMigrator,
    ) : SecureStorageInitializer {
        override suspend fun prepare(): StorageMigrationReport {
            withContext(Dispatchers.IO) { database.get().openHelper.writableDatabase }
            return storageMigrator.migrateIfNeeded()
        }
    }
