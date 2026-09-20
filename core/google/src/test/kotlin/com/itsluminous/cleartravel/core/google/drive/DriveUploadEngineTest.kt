package com.itsluminous.cleartravel.core.google.drive

import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.google.FakeAttachmentRepository
import com.itsluminous.cleartravel.core.google.FakeFlightRepository
import com.itsluminous.cleartravel.core.google.FakeGoogleLinkStore
import com.itsluminous.cleartravel.core.google.GoogleScopes
import com.itsluminous.cleartravel.core.google.auth.GoogleLinkSnapshot
import com.itsluminous.cleartravel.core.model.Attachment
import com.itsluminous.cleartravel.core.model.AttachmentOwnerType
import com.itsluminous.cleartravel.core.model.FlightJourney
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant

/** In-memory [DriveClient] recording folders/files — tests never touch the live API. */
class FakeDriveClient : DriveClient {
    data class StoredFile(
        val fileId: String,
        val name: String,
        val parentId: String,
        val bytes: ByteArray,
        val createdAt: Instant,
    )

    val folders = mutableMapOf<String, String>()
    val files = mutableListOf<StoredFile>()
    var failNextUpload = false
    var failDownloads = false
    var downloadBody: ByteArray = "drive-bytes".toByteArray()
    private var nextId = 1
    private var uploadClock = Instant.parse("2026-01-01T00:00:00Z")

    override suspend fun findFolder(name: String): String? = folders.entries.firstOrNull { it.value == name }?.key

    override suspend fun createFolder(name: String): String {
        val id = "folder-${folders.size + 1}"
        folders[id] = name
        return id
    }

    override suspend fun uploadFile(
        name: String,
        mimeType: String,
        parentId: String,
        sourceFile: File,
    ): String {
        if (failNextUpload) {
            failNextUpload = false
            throw IllegalStateException("upload failed")
        }
        val id = "file-${nextId++}"
        uploadClock = uploadClock.plusSeconds(60)
        files += StoredFile(id, name, parentId, sourceFile.readBytes(), uploadClock)
        return id
    }

    override suspend fun downloadFile(
        fileId: String,
        target: File,
    ) {
        if (failDownloads) throw IllegalStateException("download failed")
        val stored = files.firstOrNull { it.fileId == fileId }
        target.writeBytes(stored?.bytes ?: downloadBody)
    }

    override suspend fun listFiles(
        parentId: String,
        namePrefix: String,
    ): List<DriveFileInfo> =
        files
            .filter { it.parentId == parentId && it.name.startsWith(namePrefix) }
            .sortedByDescending { it.createdAt }
            .map { DriveFileInfo(it.fileId, it.name, it.bytes.size.toLong(), it.createdAt) }

    override suspend fun deleteFile(fileId: String) {
        files.removeIf { it.fileId == fileId }
    }
}

class DriveUploadEngineTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var linkStore: FakeGoogleLinkStore
    private lateinit var attachments: FakeAttachmentRepository
    private lateinit var flights: FakeFlightRepository
    private lateinit var drive: FakeDriveClient

    @Before
    fun setUp() {
        linkStore =
            FakeGoogleLinkStore(
                GoogleLinkSnapshot(
                    email = "traveler@example.com",
                    grantedScopes = setOf(GoogleScopes.DRIVE_FILE),
                    driveUploadsEnabled = true,
                ),
            )
        attachments = FakeAttachmentRepository()
        flights = FakeFlightRepository()
        drive = FakeDriveClient()
    }

    private fun engine(): DriveUploadEngine {
        val resolver = DriveFolderResolver(linkStore, drive, "ClearTravel")
        return DriveUploadEngine(linkStore, attachments, flights, drive, resolver)
    }

    private fun localFile(name: String): File = tmp.newFile(name).apply { writeText("bytes-of-$name") }

    @Test
    fun `skips when uploads are disabled or not linked`() =
        runTest {
            linkStore.setDriveUploadsEnabled(false)
            assertThat(engine().processQueue()).isEqualTo(DriveUploadResult.Skipped)

            linkStore.clear()
            assertThat(engine().processQueue()).isEqualTo(DriveUploadResult.Skipped)
        }

    @Test
    fun `uploads pending attachments into the ClearTravel folder and persists driveFileId`() =
        runTest {
            val file = localFile("ticket.pdf")
            val attachment =
                attachments.save(
                    Attachment(
                        ownerType = AttachmentOwnerType.TRAIN,
                        ownerId = "ticket-1",
                        localPath = file.absolutePath,
                        mimeType = "application/pdf",
                    ),
                )

            val result = engine().processQueue()

            assertThat(result).isEqualTo(DriveUploadResult.Done(uploaded = 1, failed = 0))
            assertThat(drive.folders.values).containsExactly("ClearTravel")
            assertThat(attachments.getAttachment(attachment.id)!!.driveFileId).isEqualTo("file-1")
            assertThat(attachments.getPendingDriveUploads()).isEmpty()
        }

    @Test
    fun `the drive folder is created once and its id cached`() =
        runTest {
            val fileA = localFile("a.pdf")
            val fileB = localFile("b.pdf")
            attachments.save(Attachment(ownerType = AttachmentOwnerType.TRAIN, ownerId = "t", localPath = fileA.absolutePath))
            engine().processQueue()
            attachments.save(Attachment(ownerType = AttachmentOwnerType.TRAIN, ownerId = "t", localPath = fileB.absolutePath))
            engine().processQueue()

            assertThat(drive.folders).hasSize(1)
            assertThat(linkStore.current().driveFolderId).isNotNull()
        }

    @Test
    fun `a failed upload stays pending for the next retry pass`() =
        runTest {
            val file = localFile("ticket.pdf")
            attachments.save(Attachment(ownerType = AttachmentOwnerType.TRAIN, ownerId = "t", localPath = file.absolutePath))
            drive.failNextUpload = true

            val first = engine().processQueue()
            assertThat(first).isEqualTo(DriveUploadResult.Done(uploaded = 0, failed = 1))
            assertThat(attachments.getPendingDriveUploads()).hasSize(1)

            val second = engine().processQueue()
            assertThat(second).isEqualTo(DriveUploadResult.Done(uploaded = 1, failed = 0))
        }

    @Test
    fun `a missing local file is skipped, not retried forever`() =
        runTest {
            attachments.save(Attachment(ownerType = AttachmentOwnerType.TRAIN, ownerId = "t", localPath = "/nonexistent/x.pdf"))

            val result = engine().processQueue()

            assertThat(result).isEqualTo(DriveUploadResult.Done(uploaded = 0, failed = 0))
        }

    @Test
    fun `boarding passes register as flight attachments exactly once`() =
        runTest {
            val pass = localFile("boarding-pass.pdf")
            val flight = FlightJourney(airlineIata = "6E", flightNumber = "1", boardingPassPath = pass.absolutePath)
            flights.save(flight)

            engine().processQueue()
            engine().processQueue()

            val rows = attachments.observeForOwner(AttachmentOwnerType.FLIGHT, flight.id).first()
            assertThat(rows).hasSize(1)
            assertThat(rows.single().driveFileId).isNotNull()
            assertThat(rows.single().mimeType).isEqualTo("application/pdf")
        }
}

class AttachmentFileResolverTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var attachments: FakeAttachmentRepository
    private lateinit var drive: FakeDriveClient

    @Before
    fun setUp() {
        attachments = FakeAttachmentRepository()
        drive = FakeDriveClient()
    }

    private fun resolver() = AttachmentFileResolver(attachments, drive, File(tmp.root, "attachments"))

    @Test
    fun `existing local file wins without touching drive`() =
        runTest {
            val file = tmp.newFile("local.pdf").apply { writeText("local") }
            drive.failDownloads = true
            val attachment =
                Attachment(ownerType = AttachmentOwnerType.TRAIN, ownerId = "t", localPath = file.absolutePath, driveFileId = "file-9")

            val result = resolver().resolve(attachment)

            assertThat(result).isEqualTo(ResolvedAttachment.Available(file))
        }

    @Test
    fun `missing local file downloads from drive and re-points localPath`() =
        runTest {
            val attachment =
                attachments.save(
                    Attachment(ownerType = AttachmentOwnerType.TRAIN, ownerId = "t", localPath = "/gone.pdf", driveFileId = "file-9"),
                )

            val result = resolver().resolve(attachment)

            val available = result as ResolvedAttachment.Available
            assertThat(available.file.readBytes()).isEqualTo(drive.downloadBody)
            assertThat(attachments.getAttachment(attachment.id)!!.localPath).isEqualTo(available.file.absolutePath)
        }

    @Test
    fun `no drive id and no local file resolves to placeholder`() =
        runTest {
            val attachment = Attachment(ownerType = AttachmentOwnerType.TRAIN, ownerId = "t", localPath = "/gone.pdf")

            assertThat(resolver().resolve(attachment)).isEqualTo(ResolvedAttachment.Placeholder)
        }

    @Test
    fun `failed drive download resolves to placeholder, keeping the row pending`() =
        runTest {
            drive.failDownloads = true
            val attachment =
                attachments.save(
                    Attachment(ownerType = AttachmentOwnerType.TRAIN, ownerId = "t", localPath = "/gone.pdf", driveFileId = "file-9"),
                )

            assertThat(resolver().resolve(attachment)).isEqualTo(ResolvedAttachment.Placeholder)
            assertThat(attachments.getAttachment(attachment.id)!!.localPath).isEqualTo("/gone.pdf")
        }
}
