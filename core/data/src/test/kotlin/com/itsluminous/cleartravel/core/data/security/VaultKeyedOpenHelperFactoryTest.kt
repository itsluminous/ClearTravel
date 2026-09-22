package com.itsluminous.cleartravel.core.data.security

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.database.ClearTravelDatabase
import com.itsluminous.cleartravel.core.database.entity.toEntity
import com.itsluminous.cleartravel.core.database.entity.toModel
import com.itsluminous.cleartravel.core.security.vault.VaultLockedException
import com.itsluminous.cleartravel.core.testing.Fixtures
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class VaultKeyedOpenHelperFactoryTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private class RecordingMigrator : DatabaseEncryptionMigrator {
        val calls = mutableListOf<Pair<File, ByteArray>>()

        override fun migrateIfNeeded(
            databaseFile: File,
            rawKey: ByteArray,
        ): Boolean {
            calls += databaseFile to rawKey
            return false
        }
    }

    private fun build(
        keyProvider: () -> ByteArray,
        migrator: DatabaseEncryptionMigrator,
    ): ClearTravelDatabase =
        Room
            .databaseBuilder(context, ClearTravelDatabase::class.java, "lazy-test.db")
            .openHelperFactory(VaultKeyedOpenHelperFactory(keyProvider, migrator) { FrameworkSQLiteOpenHelperFactory() })
            .allowMainThreadQueries()
            .build()

    @Test
    fun buildingTheDatabase_doesNotTouchTheKey_firstQueryDoes() =
        runTest {
            var keyRequests = 0
            val migrator = RecordingMigrator()
            val key = ByteArray(32) { 7 }
            val db =
                build({
                    keyRequests++
                    key
                }, migrator)
            try {
                assertThat(keyRequests).isEqualTo(0)
                assertThat(migrator.calls).isEmpty()

                val trip = Fixtures.trip()
                db.tripDao().upsert(trip.toEntity())
                assertThat(db.tripDao().getById(trip.id)?.toModel()).isEqualTo(trip)

                assertThat(keyRequests).isEqualTo(1) // one delegate, one key fetch
                assertThat(migrator.calls).hasSize(1)
                assertThat(
                    migrator.calls
                        .single()
                        .first.name,
                ).isEqualTo("lazy-test.db")
                assertThat(migrator.calls.single().second).isEqualTo(key)
            } finally {
                db.close()
                context.deleteDatabase("lazy-test.db")
            }
        }

    @Test
    fun lockedVault_surfacesAsVaultLockedException_onAccess_notOnBuild() {
        val db = build({ throw VaultLockedException() }, RecordingMigrator())
        try {
            assertThrows(VaultLockedException::class.java) { db.openHelper.writableDatabase }
        } finally {
            db.close()
        }
    }

    @Test
    fun sqliteFiles_headerDetection_andCompanions() {
        val plain = File(folder.root, "plain.db").apply { writeBytes(SqliteFiles.PLAINTEXT_HEADER + ByteArray(100)) }
        val encrypted = File(folder.root, "enc.db").apply { writeBytes(ByteArray(116) { (it * 31).toByte() }) }
        val missing = File(folder.root, "none.db")
        assertThat(SqliteFiles.isPlaintextDatabase(plain)).isTrue()
        assertThat(SqliteFiles.isPlaintextDatabase(encrypted)).isFalse()
        assertThat(SqliteFiles.isPlaintextDatabase(missing)).isFalse()
        assertThat(SqliteFiles.companionFiles(plain).map { it.name }).containsExactly("plain.db-wal", "plain.db-shm", "plain.db-journal")
    }

    @Test
    fun rawKeyPassphrase_isSqlCipherHexForm() {
        val key = ByteArray(32) { it.toByte() }
        val passphrase = SqlCipherKeys.rawKeyPassphrase(key).decodeToString()
        assertThat(passphrase).startsWith("x'")
        assertThat(passphrase).endsWith("'")
        assertThat(passphrase.length).isEqualTo(3 + 64)
        assertThrows(IllegalArgumentException::class.java) { SqlCipherKeys.rawKeyPassphrase(ByteArray(16)) }
    }
}
