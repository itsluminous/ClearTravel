package com.itsluminous.cleartravel.core.google.drive

import com.itsluminous.cleartravel.core.data.repository.AttachmentRepository
import com.itsluminous.cleartravel.core.data.repository.FlightRepository
import com.itsluminous.cleartravel.core.google.auth.GoogleLinkStore
import com.itsluminous.cleartravel.core.model.Attachment
import com.itsluminous.cleartravel.core.model.AttachmentOwnerType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.io.File

/**
 * Find-or-create of the app's "ClearTravel" Drive folder; the id is cached in the
 * link store and re-resolved when the folder disappears server-side.
 */
class DriveFolderResolver(
    private val linkStore: GoogleLinkStore,
    private val driveClient: DriveClient,
    private val folderName: String,
) {
    suspend fun ensureFolder(): String {
        linkStore.current().driveFolderId?.let { return it }
        val id = driveClient.findFolder(folderName) ?: driveClient.createFolder(folderName)
        linkStore.setDriveFolderId(id)
        return id
    }

    /** Drops the cached id (the folder 404'd) so the next pass re-resolves it. */
    suspend fun invalidate() {
        linkStore.setDriveFolderId(null)
    }
}

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
 */
class DriveUploadEngine(
    private val linkStore: GoogleLinkStore,
    private val attachmentRepository: AttachmentRepository,
    private val flightRepository: FlightRepository,
    private val driveClient: DriveClient,
    private val folderResolver: DriveFolderResolver,
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
        for (attachment in pending) {
            val file = File(attachment.localPath)
            if (!file.isFile) continue // Nothing to upload; the restore ladder owns missing files.
            try {
                val fileId =
                    driveClient.uploadFile(
                        name = file.name,
                        mimeType = attachment.mimeType.ifBlank { DEFAULT_MIME },
                        parentId = folderId,
                        sourceFile = file,
                    )
                attachmentRepository.save(attachment.copy(driveFileId = fileId))
                uploaded++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed++
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

    private companion object {
        const val DEFAULT_MIME = "application/octet-stream"
    }
}
