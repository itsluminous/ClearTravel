package com.itsluminous.cleartravel.core.data.security

import com.itsluminous.cleartravel.core.database.ClearTravelDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * The work that must finish between a successful unlock and revealing the UI
 * (ADR-031): open the database (which converts a plaintext database in place on the
 * first post-update open) and run the one-time file migration. Idempotent and cheap
 * once both migrations are done — one `PRAGMA`-level open and a flag read.
 *
 * Failures propagate so the unlock screen can show "could not secure your data"
 * and offer a retry rather than continuing with a half-migrated store.
 */
@Singleton
class SecureStorageInitializer
    @Inject
    constructor(
        private val database: Provider<ClearTravelDatabase>,
        private val storageMigrator: StorageEncryptionMigrator,
    ) {
        suspend fun prepare(): StorageMigrationReport {
            withContext(Dispatchers.IO) { database.get().openHelper.writableDatabase }
            return storageMigrator.migrateIfNeeded()
        }
    }
