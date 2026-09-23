package com.itsluminous.cleartravel.core.google.drive

import com.itsluminous.cleartravel.core.google.auth.GoogleLinkStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The ONE identity of the app's "Clear Travel" Drive folder (ADR-038).
 *
 * Find-or-create used to be a bare check-then-act on a cached id: the backup worker
 * and the upload worker, running concurrently in the same process, both saw no cached
 * id, both found nothing and both created a folder — two "Clear Travel" folders with
 * the same timestamp, uploads in one and backup ZIPs in the other. This resolver is a
 * singleton and serialises every resolve behind a [Mutex], so a second concurrent
 * caller waits and reuses the folder the first one settled on. Should duplicates
 * still exist (created by an older build), every device deterministically adopts the
 * OLDEST folder — files in others remain visible to [existingFolderIds] readers but
 * new writes all land in the canonical one.
 */
class DriveFolderResolver(
    private val linkStore: GoogleLinkStore,
    private val driveClient: DriveClient,
    private val folderName: String,
) {
    private val mutex = Mutex()

    /** Find-or-create of the canonical folder; concurrent callers share one result. */
    suspend fun ensureFolder(): String =
        mutex.withLock {
            val cached = linkStore.current().driveFolderId
            if (cached != null) return@withLock cached
            val canonical =
                driveClient.findFolders(folderName).firstOrNull()?.folderId
                    ?: driveClient.createFolder(folderName)
            linkStore.setDriveFolderId(canonical)
            canonical
        }

    /**
     * Ids of every existing app-visible folder with the app's name, oldest first —
     * for READ paths (listing backups) that must never create a folder.
     */
    suspend fun existingFolderIds(): List<String> = driveClient.findFolders(folderName).map { it.folderId }

    /** Drops the cached id (the folder 404'd) so the next pass re-resolves it. */
    suspend fun invalidate() {
        linkStore.setDriveFolderId(null)
    }
}
