package com.itsluminous.cleartravel.core.data.security

import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.security.file.LocalFileCipher
import com.itsluminous.cleartravel.core.security.file.PortableCipher
import com.itsluminous.cleartravel.core.security.vault.DefaultKeyVault
import com.itsluminous.cleartravel.core.security.vault.InMemoryKeyFileStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class StorageEncryptionMigratorTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val store = InMemoryKeyFileStore()
    private lateinit var vault: DefaultKeyVault
    private lateinit var cipher: LocalFileCipher
    private lateinit var migrator: StorageEncryptionMigrator

    @Before
    fun setUp() =
        runTest {
            vault = DefaultKeyVault(store, iterations = 1_000, ioDispatcher = UnconfinedTestDispatcher())
            vault.setUp("pw".toCharArray())
            // Simulate an install that existed before ADR-031: the flag is clear.
            store.write(store.current!!.copy(filesMigrated = false))
            cipher = LocalFileCipher(key = { vault.fileKey() })
            migrator = StorageEncryptionMigrator(folder.root, vault, cipher, UnconfinedTestDispatcher())
        }

    private fun plaintext(
        dir: File,
        name: String,
        content: String,
    ): File = File(dir.apply { mkdirs() }, name).apply { writeText(content) }

    @Test
    fun encryptsEveryUserFile_wrapsBackups_thenFlagsDone() =
        runTest {
            val doc = plaintext(AppFileLayout.documents(folder.root), "d1.png", "passport bytes")
            val pass = plaintext(AppFileLayout.boardingPasses(folder.root), "f1.pdf", "pass bytes")
            val attachment = plaintext(AppFileLayout.attachments(folder.root), "a1", "booking bytes")
            val backup = plaintext(AppFileLayout.backups(folder.root), "cleartravel-backup-20260101-000000.zip", "PK zip bytes")

            val report = migrator.migrateIfNeeded()

            assertThat(report).isEqualTo(StorageMigrationReport(filesEncrypted = 3, backupsWrapped = 1, failures = 0))
            for (file in listOf(doc, pass, attachment)) {
                assertThat(cipher.isEncrypted(file)).isTrue()
            }
            assertThat(cipher.openDecrypted(doc).use { it.readBytes().decodeToString() }).isEqualTo("passport bytes")
            assertThat(PortableCipher.isEnvelope(backup)).isTrue()
            assertThat(PortableCipher.openDecrypted(backup, vault.portableKey()).use { it.readBytes().decodeToString() })
                .isEqualTo("PK zip bytes")
            assertThat(vault.filesMigrated()).isTrue()
        }

    @Test
    fun secondRun_isANoOp_evenWithNewPlaintextDroppedIn() =
        runTest {
            plaintext(AppFileLayout.documents(folder.root), "d1.png", "x")
            migrator.migrateIfNeeded()
            val late = plaintext(AppFileLayout.documents(folder.root), "late.png", "y")

            val report = migrator.migrateIfNeeded()

            assertThat(report).isEqualTo(StorageMigrationReport.NOTHING)
            assertThat(cipher.isEncrypted(late)).isFalse() // flag set → directories are not rescanned
        }

    @Test
    fun alreadyEncryptedFiles_areLeftAlone_andCounted_zero() =
        runTest {
            val dir = AppFileLayout.documents(folder.root).apply { mkdirs() }
            val done = File(dir, "done.pdf")
            cipher.encryptTo("already".byteInputStream(), done)
            val bytesBefore = done.readBytes()

            val report = migrator.migrateIfNeeded()

            assertThat(report.filesEncrypted).isEqualTo(0)
            assertThat(done.readBytes()).isEqualTo(bytesBefore)
            assertThat(vault.filesMigrated()).isTrue()
        }

    @Test
    fun freshInstall_flagAlreadySet_doesNothing() =
        runTest {
            store.write(store.current!!.copy(filesMigrated = true))
            val doc = plaintext(AppFileLayout.documents(folder.root), "d1.png", "x")
            assertThat(migrator.migrateIfNeeded()).isEqualTo(StorageMigrationReport.NOTHING)
            assertThat(cipher.isEncrypted(doc)).isFalse()
        }
}
