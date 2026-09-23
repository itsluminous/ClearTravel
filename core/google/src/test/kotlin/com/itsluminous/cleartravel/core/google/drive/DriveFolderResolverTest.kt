package com.itsluminous.cleartravel.core.google.drive

import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.google.FakeGoogleLinkStore
import com.itsluminous.cleartravel.core.google.GoogleScopes
import com.itsluminous.cleartravel.core.google.auth.GoogleLinkSnapshot
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Before
import org.junit.Test

/** ADR-038: one folder identity — serialised resolve, oldest-first adoption. */
class DriveFolderResolverTest {
    private lateinit var linkStore: FakeGoogleLinkStore
    private lateinit var drive: FakeDriveClient

    @Before
    fun setUp() {
        linkStore =
            FakeGoogleLinkStore(
                GoogleLinkSnapshot(
                    email = "traveler@example.com",
                    grantedScopes = setOf(GoogleScopes.DRIVE_FILE),
                    driveUploadsEnabled = true,
                    driveBackupEnabled = true,
                ),
            )
        drive = FakeDriveClient()
    }

    private fun resolver() = DriveFolderResolver(linkStore, drive, FOLDER)

    @Test
    fun `concurrent resolves with no cached id create exactly ONE folder`() =
        runTest {
            // The lookup suspends before answering, exactly like a network round-trip:
            // without serialisation both callers see "no folder" and both create one.
            drive.onFindFolders = { yield() }
            val resolver = resolver()

            val ids = listOf(async { resolver.ensureFolder() }, async { resolver.ensureFolder() }).awaitAll()

            assertThat(drive.folders.values).containsExactly(FOLDER)
            assertThat(ids.toSet()).hasSize(1)
            assertThat(linkStore.current().driveFolderId).isEqualTo(ids.first())
        }

    @Test
    fun `an existing folder is adopted, not re-created, and its id cached`() =
        runTest {
            val existing = drive.seedFolder(FOLDER)

            assertThat(resolver().ensureFolder()).isEqualTo(existing)

            assertThat(drive.folders).hasSize(1)
            assertThat(linkStore.current().driveFolderId).isEqualTo(existing)
        }

    @Test
    fun `pre-existing duplicates deterministically adopt the OLDEST folder`() =
        runTest {
            val oldest = drive.seedFolder(FOLDER)
            drive.seedFolder(FOLDER)

            assertThat(resolver().ensureFolder()).isEqualTo(oldest)
            assertThat(linkStore.current().driveFolderId).isEqualTo(oldest)
        }

    @Test
    fun `the cached id is trusted without a network round-trip`() =
        runTest {
            drive.seedFolder(FOLDER)
            val resolver = resolver()

            resolver.ensureFolder()
            resolver.ensureFolder()
            resolver.ensureFolder()

            assertThat(drive.findFolderCalls).isEqualTo(1)
        }

    @Test
    fun `a cached id whose folder is gone re-resolves after invalidate`() =
        runTest {
            linkStore.setDriveFolderId("stale-folder")
            val resolver = resolver()

            // The cache is trusted until a caller hits the 404 and invalidates.
            assertThat(resolver.ensureFolder()).isEqualTo("stale-folder")
            resolver.invalidate()
            val id = resolver.ensureFolder()

            assertThat(id).isNotEqualTo("stale-folder")
            assertThat(drive.folders.values).containsExactly(FOLDER)
            assertThat(linkStore.current().driveFolderId).isEqualTo(id)
        }

    @Test
    fun `existingFolderIds lists every same-name folder oldest first and never creates one`() =
        runTest {
            assertThat(resolver().existingFolderIds()).isEmpty()
            assertThat(drive.folders).isEmpty()

            val a = drive.seedFolder(FOLDER)
            val b = drive.seedFolder(FOLDER)
            drive.seedFolder("Other")

            assertThat(resolver().existingFolderIds()).containsExactly(a, b).inOrder()
        }

    private companion object {
        const val FOLDER = "Clear Travel"
    }
}
