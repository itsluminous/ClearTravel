package com.itsluminous.cleartravel.core.data.security

import com.itsluminous.cleartravel.core.data.repository.TravelDocumentStorage
import java.io.File

/**
 * The app-private directories holding user files (ADR-031). Listed in ONE place so
 * the feature stores (writers), the backup engine (bundling/restore) and the
 * one-time encryption migration agree on what is encrypted at rest:
 *
 * - `files/documents/<id>.<ext>` — travel documents (ADR-027)
 * - `files/attachments/<id>[.<ext>]` — attachment rows: booking confirmations,
 *   Drive-restored files (ADR-015/016/017)
 * - `files/boarding_passes/<flightId>.<ext>` — the flight row's boarding-pass path
 * - `files/backups/` — the newest local backup ZIPs (portable envelopes since v2)
 *
 * Everything under `cacheDir` (share copies, Drive downloads, temp files) is
 * transient and plaintext by intent.
 */
object AppFileLayout {
    const val DOCUMENTS_DIR = TravelDocumentStorage.DIRECTORY_NAME
    const val ATTACHMENTS_DIR = "attachments"
    const val BOARDING_PASSES_DIR = "boarding_passes"
    const val BACKUPS_DIR = "backups"

    fun documents(filesDir: File): File = TravelDocumentStorage.directory(filesDir)

    fun attachments(filesDir: File): File = File(filesDir, ATTACHMENTS_DIR)

    fun boardingPasses(filesDir: File): File = File(filesDir, BOARDING_PASSES_DIR)

    fun backups(filesDir: File): File = File(filesDir, BACKUPS_DIR)

    /** Directories whose files are CTEF-encrypted (backups use the portable envelope instead). */
    fun encryptedFileDirectories(filesDir: File): List<File> = listOf(documents(filesDir), attachments(filesDir), boardingPasses(filesDir))
}
