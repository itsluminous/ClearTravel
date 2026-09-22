package com.itsluminous.cleartravel.core.google.drive

import com.itsluminous.cleartravel.core.data.repository.AttachmentRepository
import com.itsluminous.cleartravel.core.model.Attachment
import com.itsluminous.cleartravel.core.security.file.LocalFileCipher
import com.itsluminous.cleartravel.core.security.file.PortableCipher
import com.itsluminous.cleartravel.core.security.vault.KeyVault
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

    /**
     * The Drive copy is a portable envelope sealed under a password/salt this vault
     * does not hold (uploaded by another install / before a password change); the
     * bytes are on Drive but need the source password. Surfacing this to the user
     * with a prompt is follow-up UI work (ADR-031).
     */
    data object NeedsSourcePassword : ResolvedAttachment
}

/**
 * The restore ladder for attachment files (spec feature 5): **local file → Drive
 * download → placeholder**. Integration point for every place an attachment's bytes
 * are read (train/flight detail sheets, boarding-pass full-screen view): call
 * [resolve] instead of opening `localPath` directly.
 *
 * A successful Drive download lands in [attachmentsDir]`/<attachmentId>` and the
 * row's `localPath` is re-pointed at it (the local copy becomes primary again).
 *
 * ADR-031: Drive copies are portable envelopes (see [DriveUploadEngine]); the
 * download is opened with the vault's own or an adopted portable key and stored
 * CTEF-encrypted like every local file. A legacy plaintext upload (pre-encryption)
 * is simply encrypted on the way in. A foreign envelope resolves to
 * [ResolvedAttachment.NeedsSourcePassword] and is not kept.
 */
class AttachmentFileResolver(
    private val attachmentRepository: AttachmentRepository,
    private val driveClient: DriveClient,
    /** Usually `context.filesDir/attachments` — injectable for tests. */
    private val attachmentsDir: File,
    private val keyVault: KeyVault,
    private val fileCipher: LocalFileCipher,
) {
    suspend fun resolve(attachment: Attachment): ResolvedAttachment {
        // Rung 1: the local copy is the primary offline source.
        val local = File(attachment.localPath)
        if (local.isFile) return ResolvedAttachment.Available(local)

        // Rung 2: re-fetch from Drive (fresh install / cleared storage).
        val driveFileId = attachment.driveFileId ?: return ResolvedAttachment.Placeholder
        val target = File(attachmentsDir.apply { mkdirs() }, attachment.id)
        val download = File(attachmentsDir, attachment.id + DOWNLOAD_SUFFIX)
        return try {
            driveClient.downloadFile(driveFileId, download)
            if (!download.isFile || download.length() == 0L) return ResolvedAttachment.Placeholder
            if (PortableCipher.isEnvelope(download)) {
                val header = PortableCipher.readHeader(download)
                val key = keyVault.portableKeyFor(header.salt, header.iterations) ?: return ResolvedAttachment.NeedsSourcePassword
                PortableCipher.openDecrypted(download, key).use { plain -> fileCipher.encryptTo(plain, target) }
            } else {
                download.inputStream().buffered().use { plain -> fileCipher.encryptTo(plain, target) }
            }
            attachmentRepository.save(attachment.copy(localPath = target.absolutePath))
            ResolvedAttachment.Available(target)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Rung 3: offline / revoked / tampered — the caller renders a placeholder.
            target.delete()
            ResolvedAttachment.Placeholder
        } finally {
            download.delete()
        }
    }
}

private const val DOWNLOAD_SUFFIX = ".download"
