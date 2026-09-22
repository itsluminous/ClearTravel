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
import com.itsluminous.cleartravel.core.security.crypto.CryptoPrimitives
import com.itsluminous.cleartravel.core.security.file.LocalFileCipher
import com.itsluminous.cleartravel.core.security.file.PortableCipher
import com.itsluminous.cleartravel.core.security.vault.DefaultKeyVault
import com.itsluminous.cleartravel.core.security.vault.InMemoryKeyFileStore
import com.itsluminous.cleartravel.core.security.vault.PortableKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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

/** An unlocked test vault (password "pw", cheap KDF) plus its file cipher — ADR-031 fixtures. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class TestVault(
    password: String = "pw",
) {
    val vault: DefaultKeyVault =
        DefaultKeyVault(InMemoryKeyFileStore(), iterations = 1_000, ioDispatcher = UnconfinedTestDispatcher()).also {
            runBlocking { it.setUp(password.toCharArray()) }
        }
    val cipher: LocalFileCipher = LocalFileCipher(key = { vault.fileKey() })

    /** Writes [content] the way the app stores files: CTEF-encrypted under this vault. */
    fun storeEncrypted(
        file: File,
        content: String,
    ): File = file.also { cipher.encryptTo(content.byteInputStream(), it) }

    fun plaintextOf(file: File): String = cipher.openDecrypted(file).use { it.readBytes().decodeToString() }

    /** A CTEB envelope of [content] as another install with [password] would upload it. */
    fun envelopeFrom(
        content: String,
        key: PortableKey = vault.portableKey(),
    ): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        PortableCipher.encryptingStream(out, key).use { it.write(content.encodeToByteArray()) }
        return out.toByteArray()
    }
}

class DriveUploadEngineTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val testVault = TestVault()

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
        return DriveUploadEngine(
            linkStore = linkStore,
            attachmentRepository = attachments,
            flightRepository = flights,
            driveClient = drive,
            folderResolver = resolver,
            keyVault = testVault.vault,
            fileCipher = testVault.cipher,
            scratchDir = File(tmp.root, "scratch"),
        )
    }

    private fun localFile(name: String): File = testVault.storeEncrypted(tmp.newFile(name), "bytes-of-$name")

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
    fun `what reaches drive is a password envelope of the plaintext, never the on-device bytes`() =
        runTest {
            val file = localFile("ticket.pdf")
            attachments.save(Attachment(ownerType = AttachmentOwnerType.TRAIN, ownerId = "t", localPath = file.absolutePath))

            engine().processQueue()

            val stored = drive.files.single()
            assertThat(stored.name).isEqualTo("ticket.pdf" + DriveUploadEngine.ENVELOPE_SUFFIX)
            assertThat(stored.bytes.copyOf(4)).isEqualTo(PortableCipher.MAGIC)
            assertThat(stored.bytes).isNotEqualTo(file.readBytes()) // not the CTEF file
            val opened =
                PortableCipher
                    .openDecrypted(tmp.newFile("dl").apply { writeBytes(stored.bytes) }, testVault.vault.portableKey())
                    .use { it.readBytes().decodeToString() }
            assertThat(opened).isEqualTo("bytes-of-ticket.pdf")
            assertThat(File(tmp.root, "scratch").listFiles().orEmpty()).isEmpty() // scratch copy removed
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

    private val testVault = TestVault()
    private lateinit var attachments: FakeAttachmentRepository
    private lateinit var drive: FakeDriveClient

    @Before
    fun setUp() {
        attachments = FakeAttachmentRepository()
        drive = FakeDriveClient()
        // Default Drive body: an envelope from THIS vault, as the upload engine writes it.
        drive.downloadBody = testVault.envelopeFrom("drive-bytes")
    }

    private fun resolver() = AttachmentFileResolver(attachments, drive, File(tmp.root, "attachments"), testVault.vault, testVault.cipher)

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
            // Stored like every local file: CTEF under this install's key, plaintext restored.
            assertThat(testVault.cipher.isEncrypted(available.file)).isTrue()
            assertThat(testVault.plaintextOf(available.file)).isEqualTo("drive-bytes")
            assertThat(attachments.getAttachment(attachment.id)!!.localPath).isEqualTo(available.file.absolutePath)
            assertThat(File(tmp.root, "attachments").listFiles()!!.map { it.name }).containsExactly(attachment.id)
        }

    @Test
    fun `a legacy plaintext drive upload is encrypted on the way in`() =
        runTest {
            drive.downloadBody = "plain-old-upload".encodeToByteArray()
            val attachment =
                attachments.save(
                    Attachment(ownerType = AttachmentOwnerType.TRAIN, ownerId = "t", localPath = "/gone.pdf", driveFileId = "file-9"),
                )

            val available = resolver().resolve(attachment) as ResolvedAttachment.Available

            assertThat(testVault.cipher.isEncrypted(available.file)).isTrue()
            assertThat(testVault.plaintextOf(available.file)).isEqualTo("plain-old-upload")
        }

    @Test
    fun `an envelope from another password needs the source password and is not kept`() =
        runTest {
            val other = TestVault(password = "someone-else")
            drive.downloadBody = other.envelopeFrom("foreign", other.vault.portableKey())
            val attachment =
                attachments.save(
                    Attachment(ownerType = AttachmentOwnerType.TRAIN, ownerId = "t", localPath = "/gone.pdf", driveFileId = "file-9"),
                )

            assertThat(resolver().resolve(attachment)).isEqualTo(ResolvedAttachment.NeedsSourcePassword)
            assertThat(File(tmp.root, "attachments").listFiles().orEmpty()).isEmpty()
            assertThat(attachments.getAttachment(attachment.id)!!.localPath).isEqualTo("/gone.pdf")

            // Once the source password has been proven elsewhere (e.g. a backup restore),
            // the adopted key resolves it silently.
            val foreignKey = other.vault.portableKey()
            testVault.vault.adoptPortableKey(
                PortableKey(
                    foreignKey.salt,
                    foreignKey.iterations,
                    CryptoPrimitives.aesKey(foreignKey.key.encoded),
                ),
            )
            val available = resolver().resolve(attachment) as ResolvedAttachment.Available
            assertThat(testVault.plaintextOf(available.file)).isEqualTo("foreign")
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
