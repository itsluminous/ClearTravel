package com.itsluminous.cleartravel.core.data.security

import android.util.Log
import com.itsluminous.cleartravel.core.security.file.LocalFileCipher
import com.itsluminous.cleartravel.core.security.file.PortableCipher
import com.itsluminous.cleartravel.core.security.vault.KeyVault
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** What one migration pass did — surfaced in logs and asserted by tests. */
data class StorageMigrationReport(
    val filesEncrypted: Int,
    val backupsWrapped: Int,
    val failures: Int,
) {
    companion object {
        val NOTHING = StorageMigrationReport(0, 0, 0)
    }
}

/**
 * One-time plaintext → encrypted conversion of user files after the update that
 * introduced ADR-031 (a fresh install has `filesMigrated = true` from the start):
 *
 * - every regular file under [AppFileLayout.encryptedFileDirectories] without the
 *   CTEF header is rewritten in place through [LocalFileCipher.encryptInPlace];
 * - every local backup ZIP that is not yet a portable envelope is wrapped in one
 *   under the vault's own portable key (bytes unchanged — the v1 ZIP content is the
 *   v2 payload; the manifest's `schemaVersion` stays 1 and imports as before).
 *
 * Idempotent and resumable: a crash mid-way leaves already-converted files
 * header-marked and the rest plaintext; the next unlock finishes them. The
 * `filesMigrated` flag is written only after a pass with zero failures, so a file
 * that could not be read is retried next time instead of being silently skipped
 * forever. Requires an unlocked vault.
 */
class StorageEncryptionMigrator(
    private val filesDir: File,
    private val vault: KeyVault,
    private val fileCipher: LocalFileCipher,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun migrateIfNeeded(): StorageMigrationReport {
        if (vault.filesMigrated()) return StorageMigrationReport.NOTHING
        val report = withContext(ioDispatcher) { migrate() }
        if (report.failures == 0) vault.markFilesMigrated()
        Log.i(TAG, "storage migration: $report")
        return report
    }

    private fun migrate(): StorageMigrationReport {
        var files = 0
        var backups = 0
        var failures = 0
        for (dir in AppFileLayout.encryptedFileDirectories(filesDir)) {
            dir.listFiles { f -> f.isFile && !f.name.endsWith(TEMP_SUFFIX) }?.forEach { file ->
                try {
                    if (fileCipher.encryptInPlace(file)) files++
                } catch (e: Exception) {
                    failures++
                    Log.w(TAG, "could not encrypt ${file.name}", e)
                }
            }
        }
        val portableKey = vault.portableKey()
        AppFileLayout.backups(filesDir).listFiles { f -> f.isFile && f.name.endsWith(".zip") }?.forEach { zip ->
            if (PortableCipher.isEnvelope(zip)) return@forEach
            val temp = File(zip.parentFile, zip.name + TEMP_SUFFIX)
            try {
                PortableCipher.encryptingStream(temp.outputStream().buffered(), portableKey).use { out ->
                    zip.inputStream().use { it.copyTo(out) }
                }
                if (!zip.delete() || !temp.renameTo(zip)) {
                    temp.copyTo(zip, overwrite = true)
                    temp.delete()
                }
                backups++
            } catch (e: Exception) {
                temp.delete()
                failures++
                Log.w(TAG, "could not wrap backup ${zip.name}", e)
            }
        }
        return StorageMigrationReport(filesEncrypted = files, backupsWrapped = backups, failures = failures)
    }

    private companion object {
        const val TAG = "ClearTravelSecurity"
        const val TEMP_SUFFIX = ".enc-tmp"
    }
}
