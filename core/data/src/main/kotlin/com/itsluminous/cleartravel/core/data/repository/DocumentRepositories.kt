package com.itsluminous.cleartravel.core.data.repository

import com.itsluminous.cleartravel.core.model.TravelDocument
import kotlinx.coroutines.flow.Flow
import java.io.File

/**
 * Travel documents aggregate (ADR-027): first-class, un-owned files (passport, visa,
 * insurance…) kept in app-private storage. File bytes are the caller's business (the
 * feature copies into `filesDir/documents/` before saving the row and removes the
 * file after a delete); this repository owns only the Room row.
 */
interface TravelDocumentRepository {
    /** Live documents, newest first. */
    fun observeAll(): Flow<List<TravelDocument>>

    fun observeDocument(id: String): Flow<TravelDocument?>

    suspend fun getDocument(id: String): TravelDocument?

    /**
     * Live documents not yet uploaded to Drive. RESERVED: nothing drains this queue
     * today — documents are local-only until the Drive follow-up (ADR-027).
     */
    suspend fun getPendingDriveUploads(): List<TravelDocument>

    /** Upserts [document] with a bumped `updatedAt`; returns the stored copy. */
    suspend fun save(document: TravelDocument): TravelDocument

    /** Soft delete (ADR-002). */
    suspend fun delete(id: String)
}

/**
 * Where travel-document files live: `filesDir/documents/<id>.<ext>` (ADR-027). Shared
 * by the feature (writes new files there) and the backup engine (restores bundled
 * bytes there) so both agree on the layout.
 */
object TravelDocumentStorage {
    const val DIRECTORY_NAME = "documents"

    fun directory(filesDir: File): File = File(filesDir, DIRECTORY_NAME)

    /** `<id>.<extension>`, or bare `<id>` when [extension] is blank. */
    fun fileName(
        documentId: String,
        extension: String,
    ): String = if (extension.isBlank()) documentId else "$documentId.$extension"
}
