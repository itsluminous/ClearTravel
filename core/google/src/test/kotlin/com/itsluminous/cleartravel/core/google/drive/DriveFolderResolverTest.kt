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

/** ADR-038: one folder identity — serialised resolve + duplicate convergence. */
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
    fun `duplicate folders converge on the OLDEST - files move into it, the empties are trashed`() =
        runTest {
            val oldest = drive.seedFolder(FOLDER)
            val duplicate = drive.seedFolder(FOLDER)
            val ticket = drive.seedFile(oldest, "ticket.pdf.cteb")
            val backup = drive.seedFile(duplicate, "cleartravel-backup-20260101-0101.zip")
            val pass = drive.seedFile(duplicate, "boarding.pdf.cteb")

            val canonical = resolver().ensureFolder()

            assertThat(canonical).isEqualTo(oldest)
            assertThat(drive.files.map { it.fileId }).containsExactly(ticket, backup, pass)
            assertThat(drive.files.map { it.parentId }.toSet()).containsExactly(oldest)
            assertThat(drive.moveCalls).isEqualTo(2)
            assertThat(drive.folders.keys).containsExactly(oldest) // the duplicate is trashed
            assertThat(drive.folderRecords.single { it.folderId == duplicate }.trashed).isTrue()
            assertThat(linkStore.current().driveFolderId).isEqualTo(oldest)
        }

    @Test
    fun `a cached id pointing at the NEWER duplicate is replaced by the oldest folder`() =
        runTest {
            val oldest = drive.seedFolder(FOLDER)
            val newer = drive.seedFolder(FOLDER)
            drive.seedFile(newer, "cleartravel-backup-20260101-0101.zip")
            linkStore.setDriveFolderId(newer)

            val canonical = resolver().ensureFolder()

            assertThat(canonical).isEqualTo(oldest)
            assertThat(linkStore.current().driveFolderId).isEqualTo(oldest)
            assertThat(drive.files.single().parentId).isEqualTo(oldest)
            assertThat(drive.folders.keys).containsExactly(oldest)
        }

    @Test
    fun `once converged, the cached id is trusted for the rest of the process`() =
        runTest {
            drive.seedFolder(FOLDER)
            val resolver = resolver()

            resolver.ensureFolder()
            resolver.ensureFolder()
            resolver.ensureFolder()

            assertThat(drive.findFolderCalls).isEqualTo(1)
        }

    @Test
    fun `a failed convergence still returns the canonical folder and is retried next pass`() =
        runTest {
            val oldest = drive.seedFolder(FOLDER)
            val duplicate = drive.seedFolder(FOLDER)
            drive.seedFile(duplicate, "boarding.pdf.cteb")
            drive.failMoves = true
            val resolver = resolver()

            assertThat(resolver.ensureFolder()).isEqualTo(oldest)
            assertThat(drive.folders.keys).containsExactly(oldest, duplicate) // nothing trashed while files remain
            assertThat(drive.files.single().parentId).isEqualTo(duplicate)

            drive.failMoves = false
            assertThat(resolver.ensureFolder()).isEqualTo(oldest)
            assertThat(drive.files.single().parentId).isEqualTo(oldest)
            assertThat(drive.folders.keys).containsExactly(oldest)
            assertThat(drive.findFolderCalls).isEqualTo(2)
            resolver.ensureFolder()
            assertThat(drive.findFolderCalls).isEqualTo(2) // converged now → cached
        }

    @Test
    fun `a cached id whose folder is gone re-resolves instead of uploading into the void`() =
        runTest {
            linkStore.setDriveFolderId("stale-folder")

            val id = resolver().ensureFolder()

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
