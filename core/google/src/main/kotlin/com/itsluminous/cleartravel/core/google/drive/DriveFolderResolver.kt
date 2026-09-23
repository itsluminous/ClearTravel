package com.itsluminous.cleartravel.core.google.drive

import com.itsluminous.cleartravel.core.google.auth.GoogleLinkStore
import kotlinx.coroutines.CancellationException
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
 * caller waits and reuses the folder the first one settled on.
 *
 * Convergence for already-affected accounts: once per process the resolver lists
 * every live app-visible folder with the app's name (the client returns them oldest
 * first, so every device agrees), adopts the OLDEST as canonical, moves every file
 * out of each duplicate into it and trashes the emptied duplicates. Only when that
 * pass succeeds is the cached id trusted without a network round-trip for the rest
 * of the process; a failed convergence is retried on the next resolve and never
 * blocks the caller (the canonical folder is still returned).
 */
class DriveFolderResolver(
    private val linkStore: GoogleLinkStore,
    private val driveClient: DriveClient,
    private val folderName: String,
) {
    private val mutex = Mutex()

    @Volatile
    private var convergedThisProcess = false

    /** Find-or-create of the canonical folder; concurrent callers share one result. */
    suspend fun ensureFolder(): String =
        mutex.withLock {
            val cached = linkStore.current().driveFolderId
            if (cached != null && convergedThisProcess) return@withLock cached
            val folders = driveClient.findFolders(folderName)
            val canonical = folders.firstOrNull()?.folderId ?: driveClient.createFolder(folderName)
            val duplicates = folders.drop(1)
            convergedThisProcess =
                if (duplicates.isEmpty()) {
                    true
                } else {
                    converge(canonical, duplicates)
                }
            if (cached != canonical) linkStore.setDriveFolderId(canonical)
            canonical
        }

    /**
     * Ids of every existing app-visible folder with the app's name, oldest first —
     * for READ paths (listing backups) that must never create a folder and, until a
     * write pass has converged duplicates, must look in all of them.
     */
    suspend fun existingFolderIds(): List<String> = driveClient.findFolders(folderName).map { it.folderId }

    /** Drops the cached id (the folder 404'd) so the next pass re-resolves it. */
    suspend fun invalidate() {
        convergedThisProcess = false
        linkStore.setDriveFolderId(null)
    }

    /** Moves every file of each duplicate into [canonical]; trashes the ones left empty. True when all done. */
    private suspend fun converge(
        canonical: String,
        duplicates: List<DriveFolderInfo>,
    ): Boolean {
        var complete = true
        for (duplicate in duplicates) {
            try {
                driveClient.listFiles(duplicate.folderId).forEach { file ->
                    driveClient.moveFile(file.fileId, fromParentId = duplicate.folderId, toParentId = canonical)
                }
                if (driveClient.listFiles(duplicate.folderId).isEmpty()) {
                    driveClient.trashFile(duplicate.folderId)
                } else {
                    complete = false
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                complete = false // Retried on the next resolve; the canonical folder is still usable.
            }
        }
        return complete
    }
}
