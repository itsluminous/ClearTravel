package com.itsluminous.cleartravel.core.data.security

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.itsluminous.cleartravel.core.security.crypto.CryptoPrimitives
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File

/**
 * Turns the vault's raw 32-byte database key into the SQLCipher passphrase form
 * `x'<64 hex>'`, which makes SQLCipher use the bytes DIRECTLY as the page key and
 * skip its own (redundant, ~0.5 s) PBKDF2 pass on every open.
 */
object SqlCipherKeys {
    fun rawKeyPassphrase(key: ByteArray): ByteArray {
        require(key.size == CryptoPrimitives.AES_KEY_BYTES) { "database key must be 32 bytes" }
        return "x'${CryptoPrimitives.toHex(key)}'".encodeToByteArray()
    }
}

/**
 * Loads the SQLCipher native library once per process (required before any
 * `net.zetetic` class is touched).
 */
object SqlCipherRuntime {
    @Volatile
    private var loaded = false

    fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (!loaded) {
                System.loadLibrary("sqlcipher")
                loaded = true
            }
        }
    }
}

/**
 * Room open-helper factory that defers EVERYTHING key-related to the first real
 * database access (ADR-031). `Room.databaseBuilder(...).build()` calls
 * [create] eagerly while wiring the Hilt graph — long before the user has unlocked —
 * so the returned helper must not need the key yet. On the first
 * `writableDatabase`/`readableDatabase` it:
 *
 * 1. asks [keyProvider] for the 32-byte database key (throws
 *    `VaultLockedException` while locked — the UI gate and the worker checks make
 *    sure that never happens in practice);
 * 2. runs [migrator] so a pre-encryption plaintext database becomes SQLCipher
 *    (one-time, header-detected);
 * 3. builds the real SQLCipher helper via [delegateFactory] and replays the WAL
 *    setting Room applied during init.
 *
 * [delegateFactory] is injectable so the laziness can be tested on the JVM with the
 * framework SQLite helper (SQLCipher ships no host natives).
 */
class VaultKeyedOpenHelperFactory(
    private val keyProvider: () -> ByteArray,
    private val migrator: DatabaseEncryptionMigrator,
    private val delegateFactory: (rawKey: ByteArray) -> SupportSQLiteOpenHelper.Factory = ::sqlCipherFactory,
) : SupportSQLiteOpenHelper.Factory {
    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper = LazyHelper(configuration)

    private inner class LazyHelper(
        private val configuration: SupportSQLiteOpenHelper.Configuration,
    ) : SupportSQLiteOpenHelper {
        private val lock = Any()
        private var delegate: SupportSQLiteOpenHelper? = null
        private var walEnabled: Boolean? = null

        override val databaseName: String? get() = configuration.name

        override fun setWriteAheadLoggingEnabled(enabled: Boolean) {
            synchronized(lock) {
                walEnabled = enabled
                delegate?.setWriteAheadLoggingEnabled(enabled)
            }
        }

        override val writableDatabase: SupportSQLiteDatabase get() = delegate().writableDatabase

        override val readableDatabase: SupportSQLiteDatabase get() = delegate().readableDatabase

        override fun close() {
            synchronized(lock) { delegate?.close() }
        }

        private fun delegate(): SupportSQLiteOpenHelper =
            synchronized(lock) {
                delegate ?: createDelegate().also { delegate = it }
            }

        private fun createDelegate(): SupportSQLiteOpenHelper {
            val key = keyProvider()
            configuration.name?.let { name -> migrator.migrateIfNeeded(configuration.context.getDatabasePath(name), key) }
            return delegateFactory(key).create(configuration).also { real ->
                walEnabled?.let(real::setWriteAheadLoggingEnabled)
            }
        }
    }

    companion object {
        fun sqlCipherFactory(rawKey: ByteArray): SupportSQLiteOpenHelper.Factory {
            SqlCipherRuntime.ensureLoaded()
            return SupportOpenHelperFactory(SqlCipherKeys.rawKeyPassphrase(rawKey))
        }
    }
}

/** One-time plaintext → SQLCipher conversion of an existing database file. */
interface DatabaseEncryptionMigrator {
    /**
     * Encrypts [databaseFile] in place with [rawKey] when it is a plaintext SQLite
     * file; a missing file (fresh install) or an already-encrypted one is a no-op.
     * Returns true when a migration ran.
     */
    fun migrateIfNeeded(
        databaseFile: File,
        rawKey: ByteArray,
    ): Boolean
}
