package com.itsluminous.cleartravel.core.data.security

import android.util.Log
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File
import java.io.IOException

/** Pure helpers over SQLite files — unit-tested on the host. */
object SqliteFiles {
    /** The 16-byte header every PLAINTEXT SQLite 3 database starts with. */
    val PLAINTEXT_HEADER: ByteArray = "SQLite format 3\u0000".encodeToByteArray()

    /**
     * True when [file] exists and is an unencrypted SQLite database. A SQLCipher
     * database starts with its random 16-byte salt instead, so the header is a
     * reliable "already migrated" test — no separate flag can drift from the truth.
     */
    fun isPlaintextDatabase(file: File): Boolean {
        if (!file.isFile || file.length() < PLAINTEXT_HEADER.size) return false
        val head = ByteArray(PLAINTEXT_HEADER.size)
        file.inputStream().use { input ->
            var filled = 0
            while (filled < head.size) {
                val n = input.read(head, filled, head.size - filled)
                if (n < 0) return false
                filled += n
            }
        }
        return head.contentEquals(PLAINTEXT_HEADER)
    }

    /** The journal/WAL side files SQLite keeps next to [databaseFile]. */
    fun companionFiles(databaseFile: File): List<File> =
        listOf("-wal", "-shm", "-journal").map { suffix -> File(databaseFile.parentFile, databaseFile.name + suffix) }
}

/**
 * [DatabaseEncryptionMigrator] using SQLCipher's `sqlcipher_export` ATTACH pattern
 * (ADR-031): open the plaintext file with an empty key, attach a new encrypted
 * database under the raw key, export, copy `user_version` (which the export does not
 * carry), detach, then swap the files and remove the plaintext copy plus its WAL/SHM
 * companions. Runs once — afterwards [SqliteFiles.isPlaintextDatabase] is false.
 *
 * Not host-testable (native SQLCipher); the decision and file-swap logic is kept
 * in [SqliteFiles] + the factory, which are.
 */
class SqlCipherDatabaseEncryptionMigrator : DatabaseEncryptionMigrator {
    override fun migrateIfNeeded(
        databaseFile: File,
        rawKey: ByteArray,
    ): Boolean {
        if (!SqliteFiles.isPlaintextDatabase(databaseFile)) return false
        SqlCipherRuntime.ensureLoaded()
        val encrypted = File(databaseFile.parentFile, databaseFile.name + ".encrypting")
        encrypted.delete()
        SqliteFiles.companionFiles(encrypted).forEach(File::delete)
        val passphrase = SqlCipherKeys.rawKeyPassphrase(rawKey).decodeToString()
        try {
            val plain =
                SQLiteDatabase.openDatabase(
                    databaseFile.absolutePath,
                    ByteArray(0),
                    null,
                    SQLiteDatabase.OPEN_READWRITE,
                    null,
                    null,
                )
            try {
                val userVersion = plain.version
                plain.execSQL("ATTACH DATABASE ? AS encrypted KEY ?", arrayOf<Any>(encrypted.absolutePath, passphrase))
                plain.rawExecSQL("SELECT sqlcipher_export('encrypted')")
                plain.rawExecSQL("PRAGMA encrypted.user_version = $userVersion")
                plain.rawExecSQL("DETACH DATABASE encrypted")
            } finally {
                plain.close()
            }
            if (!encrypted.isFile || encrypted.length() == 0L) throw IOException("sqlcipher_export produced no file")
            // Swap: the plaintext file and its WAL/SHM go away, the encrypted one takes its name.
            SqliteFiles.companionFiles(databaseFile).forEach(File::delete)
            if (!databaseFile.delete()) throw IOException("could not remove plaintext database")
            if (!encrypted.renameTo(databaseFile)) {
                encrypted.copyTo(databaseFile, overwrite = true)
                encrypted.delete()
            }
            SqliteFiles.companionFiles(encrypted).forEach(File::delete)
            Log.i(TAG, "database encrypted in place")
            return true
        } catch (e: Exception) {
            // Leave the plaintext database untouched so the app still opens (the
            // factory will retry on the next start); never leave a half-written copy.
            encrypted.delete()
            SqliteFiles.companionFiles(encrypted).forEach(File::delete)
            Log.e(TAG, "database encryption migration failed", e)
            throw e
        }
    }

    private companion object {
        const val TAG = "ClearTravelSecurity"
    }
}
