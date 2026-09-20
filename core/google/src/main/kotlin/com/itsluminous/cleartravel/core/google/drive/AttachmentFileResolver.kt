package com.itsluminous.cleartravel.core.google.drive

import com.itsluminous.cleartravel.core.data.repository.AttachmentRepository
import com.itsluminous.cleartravel.core.model.Attachment
import kotlinx.coroutines.CancellationException
import java.io.File

/** Outcome of the restore ladder for one attachment. */
sealed interface ResolvedAttachment {
    /** The bytes are on disk at [file]. */
    data class Available(
        val file: File,
    ) : ResolvedAttachment

    /** No local file and no (reachable) Drive copy — render a placeholder. */
    data object Placeholder : ResolvedAttachment
}

/**
 * The restore ladder for attachment files (spec feature 5): **local file → Drive
 * download → placeholder**. Integration point for every place an attachment's bytes
 * are read (train/flight detail sheets, boarding-pass full-screen view): call
 * [resolve] instead of opening `localPath` directly.
 *
 * A successful Drive download lands in [attachmentsDir]`/<attachmentId>` and the
 * row's `localPath` is re-pointed at it (the local copy becomes primary again).
 */
class AttachmentFileResolver(
    private val attachmentRepository: AttachmentRepository,
    private val driveClient: DriveClient,
    /** Usually `context.filesDir/attachments` — injectable for tests. */
    private val attachmentsDir: File,
) {
    suspend fun resolve(attachment: Attachment): ResolvedAttachment {
        // Rung 1: the local copy is the primary offline source.
        val local = File(attachment.localPath)
        if (local.isFile) return ResolvedAttachment.Available(local)

        // Rung 2: re-fetch from Drive (fresh install / cleared storage).
        val driveFileId = attachment.driveFileId ?: return ResolvedAttachment.Placeholder
        val target = File(attachmentsDir.apply { mkdirs() }, attachment.id)
        return try {
            driveClient.downloadFile(driveFileId, target)
            if (target.isFile && target.length() > 0L) {
                attachmentRepository.save(attachment.copy(localPath = target.absolutePath))
                ResolvedAttachment.Available(target)
            } else {
                target.delete()
                ResolvedAttachment.Placeholder
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Rung 3: offline / revoked — the caller renders a placeholder.
            target.delete()
            ResolvedAttachment.Placeholder
        }
    }
}
