package com.itsluminous.cleartravel.core.google.drive

import com.itsluminous.cleartravel.core.data.repository.AttachmentRepository
import com.itsluminous.cleartravel.core.data.repository.FlightRepository
import com.itsluminous.cleartravel.core.google.auth.GoogleLinkStore
import com.itsluminous.cleartravel.core.model.Attachment
import com.itsluminous.cleartravel.core.model.AttachmentOwnerType
import com.itsluminous.cleartravel.core.security.file.LocalFileCipher
import com.itsluminous.cleartravel.core.security.file.PortableCipher
import com.itsluminous.cleartravel.core.security.vault.KeyVault
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.io.File

/** Outcome of one upload-queue drain. */
sealed interface DriveUploadResult {
    /** Uploads are disabled / no account linked — nothing to do. */
    data object Skipped : DriveUploadResult

    /** The pass ran; [failed] > 0 means the worker should retry with backoff. */
    data class Done(
        val uploaded: Int,
        val failed: Int,
    ) : DriveUploadResult
}

/**
 * Drains the Drive upload queue (spec feature 5): every live attachment without a
 * `driveFileId` — [AttachmentRepository.getPendingDriveUploads] — plus flight
 * boarding-pass files (registered as FLIGHT attachments so the same queue and
 * `driveFileId` column cover them, ADR-016) is uploaded into the app's "ClearTravel"
 * Drive folder. The local file stays the primary offline source; a missing local
 * file is skipped (it can never upload). Failures leave the row pending, so the
 * next pass — worker retry with backoff, or the periodic drain — picks it up again.
 *
 * ADR-031: what reaches Drive is a **portable envelope** (`CTEB`, keyed by the app
 * password, like backups) — never the on-device `CTEF` bytes, whose key is
 * per-install and would make the file useless after a reinstall. Each upload is
 * decrypted from disk and re-sealed into a scratch file that is deleted afterwards;
 * the Drive name gains the [ENVELOPE_SUFFIX] and an opaque MIME type so nothing
 * outside this app tries to open it. Requires an unlocked vault (the worker checks).
 */
class DriveUploadEngine(
    private val linkStore: GoogleLinkStore,
    private val attachmentRepository: AttachmentRepository,
    private val flightRepository: FlightRepository,
    private val driveClient: DriveClient,
    private val folderResolver: DriveFolderResolver,
    private val keyVault: KeyVault,
    private val fileCipher: LocalFileCipher,
    /** Scratch space for the sealed copies (`cacheDir/drive-uploads`). */
    private val scratchDir: File,
) {
    suspend fun processQueue(): DriveUploadResult {
        val snapshot = linkStore.current()
        if (!snapshot.isLinked || !snapshot.driveUploadsEnabled) return DriveUploadResult.Skipped

        registerBoardingPasses()

        val pending = attachmentRepository.getPendingDriveUploads()
        if (pending.isEmpty()) return DriveUploadResult.Done(uploaded = 0, failed = 0)

        val folderId = folderResolver.ensureFolder()
        var uploaded = 0
        var failed = 0
        val portableKey = keyVault.portableKey()
        for (attachment in pending) {
            val file = File(attachment.localPath)
            if (!file.isFile) continue // Nothing to upload; the restore ladder owns missing files.
            val sealed = File(scratchDir.apply { mkdirs() }, file.name + ENVELOPE_SUFFIX)
            try {
                PortableCipher.encryptingStream(sealed.outputStream().buffered(), portableKey).use { out ->
                    fileCipher.decryptTo(file, out)
                }
                val fileId =
                    driveClient.uploadFile(
                        name = sealed.name,
                        mimeType = ENVELOPE_MIME,
                        parentId = folderId,
                        sourceFile = sealed,
                    )
                attachmentRepository.save(attachment.copy(driveFileId = fileId))
                uploaded++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed++
            } finally {
                sealed.delete()
            }
        }
        return DriveUploadResult.Done(uploaded = uploaded, failed = failed)
    }

    /**
     * Boarding passes live as a file path on the flight row, not as attachment rows.
     * Register each one as a FLIGHT attachment exactly once (keyed by local path) so
     * the standard queue uploads it and stores its `driveFileId`.
     */
    private suspend fun registerBoardingPasses() {
        val flights = flightRepository.observeActive().first() + flightRepository.observeArchived().first()
        for (flight in flights) {
            val path = flight.boardingPassPath ?: continue
            val existing = attachmentRepository.observeForOwner(AttachmentOwnerType.FLIGHT, flight.id).first()
            if (existing.any { it.localPath == path }) continue
            attachmentRepository.save(
                Attachment(
                    ownerType = AttachmentOwnerType.FLIGHT,
                    ownerId = flight.id,
                    localPath = path,
                    mimeType = guessMimeType(path),
                ),
            )
        }
    }

    private fun guessMimeType(path: String): String =
        when (path.substringAfterLast('.', "").lowercase()) {
            "pdf" -> "application/pdf"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            else -> DEFAULT_MIME
        }

    companion object {
        private const val DEFAULT_MIME = "application/octet-stream"

        /** Drive-side name suffix marking a ClearTravel portable envelope. */
        const val ENVELOPE_SUFFIX = ".cteb"
        const val ENVELOPE_MIME = "application/octet-stream"
    }
}
