package com.itsluminous.cleartravel.core.data.backup

import android.net.Uri
import java.time.Instant

/**
 * Public backup engine contract (ADR-015, `docs/backup-format.md`). This is the seam
 * later milestones build on: the Google milestone uploads the file produced by
 * [exportLatestToAppStorage] to Drive and feeds a downloaded backup through
 * [importPreview]/[importApply] — no other entry points are needed.
 *
 * All methods are safe to call from any dispatcher (they switch to IO internally)
 * and report failures as [BackupException] subtypes.
 */
interface BackupManager {
    /**
     * Exports a full backup ZIP to a user-chosen SAF [uri] and refreshes the latest
     * app-storage copy (spec feature 6: "also keep the latest in app storage").
     */
    suspend fun exportToUri(uri: Uri): ExportResult

    /**
     * Exports a full backup ZIP into app storage (`filesDir/backups`), pruning to the
     * [DefaultBackupManager.MAX_LOCAL_BACKUPS] most recent files. The Google milestone
     * uploads this file to Drive after export.
     */
    suspend fun exportLatestToAppStorage(): ExportResult

    /**
     * Parses ONLY the manifest of the backup at [uri] — cheap enough to drive the
     * import confirmation dialog (date + per-entity counts) before any data changes.
     *
     * A v2 backup (ADR-031) is decrypted with this vault's own portable key when it
     * was written under the same password/salt; otherwise [sourcePassword] must carry
     * the password it was written with (the key derived from it is remembered for the
     * process, so the follow-up [importApply] needs no password again).
     *
     * @throws BackupException.PasswordRequired v2 backup from another password and no [sourcePassword].
     * @throws BackupException.WrongPassword [sourcePassword] does not open the backup.
     * @throws BackupException.UnsupportedSchemaVersion for backups from newer apps.
     * @throws BackupException.CorruptedBackup when the file is not a readable backup.
     */
    suspend fun importPreview(
        uri: Uri,
        sourcePassword: CharArray? = null,
    ): ImportPreview

    /**
     * Imports the backup at [uri] by MERGING it into the local database — never a
     * wipe. Per-row last-write-wins on `updatedAt` (ADR-002/ADR-015): backup-only
     * rows are inserted as-is (id + updatedAt preserved), local-only rows are kept,
     * same-id rows resolve entirely to the newer version including tombstone state.
     * Idempotent: importing the same file twice changes nothing the second time.
     * Password semantics as in [importPreview].
     *
     * @throws BackupException.PasswordRequired v2 backup from another password and no [sourcePassword].
     * @throws BackupException.WrongPassword [sourcePassword] does not open the backup.
     * @throws BackupException.UnsupportedSchemaVersion for backups from newer apps.
     * @throws BackupException.CorruptedBackup when the file is not a readable backup.
     */
    suspend fun importApply(
        uri: Uri,
        sourcePassword: CharArray? = null,
    ): MergeSummary

    /** Newest backup in app storage, or null when none exists yet. */
    suspend fun latestLocalBackup(): LocalBackupInfo?
}

/** Outcome of a successful export. */
data class ExportResult(
    val createdAt: Instant,
    /** Total rows written across all entity files, tombstones included. */
    val totalRows: Int,
    val sizeBytes: Long,
)

/** Manifest summary shown in the import confirmation dialog. */
data class ImportPreview(
    val schemaVersion: Int,
    val appVersion: String,
    val createdAt: Instant,
    /** Rows per entity file name, tombstones included. */
    val entityCounts: Map<String, Int>,
) {
    val totalRows: Int get() = entityCounts.values.sum()
}

/** Aggregate outcome of an applied import across all entity types. */
data class MergeSummary(
    /** Backup rows that did not exist locally and were inserted as-is. */
    val inserted: Int,
    /** Same-id rows where the backup was newer and replaced the local row entirely. */
    val updated: Int,
    /** Same-id rows where the local row was newer or identical — left untouched. */
    val skipped: Int,
) {
    operator fun plus(other: MergeSummary): MergeSummary =
        MergeSummary(
            inserted = inserted + other.inserted,
            updated = updated + other.updated,
            skipped = skipped + other.skipped,
        )

    companion object {
        val ZERO = MergeSummary(0, 0, 0)
    }
}

/** A backup file kept in app storage (`filesDir/backups`). */
data class LocalBackupInfo(
    val fileName: String,
    val createdAt: Instant,
    val sizeBytes: Long,
)

/** Typed failures of the backup engine — the UI maps each to a specific message. */
sealed class BackupException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    /**
     * The backup's manifest declares a schema version newer than this app can read
     * (spec feature 6: reject unknown newer versions gracefully).
     */
    class UnsupportedSchemaVersion(
        val found: Int,
        val supported: Int = BackupManifest.SCHEMA_VERSION,
    ) : BackupException("Backup schema version $found is newer than supported $supported")

    /** The file is not a readable ClearTravel backup (bad ZIP, missing/broken JSON). */
    class CorruptedBackup(
        cause: Throwable? = null,
    ) : BackupException("Not a readable ClearTravel backup", cause)

    /** The destination/source stream could not be opened or written/read. */
    class Io(
        cause: Throwable? = null,
    ) : BackupException("Backup I/O failed", cause)

    /**
     * The backup is a v2 envelope (ADR-031) written under a password/salt this vault
     * does not hold — the UI must ask for the source password and retry.
     */
    class PasswordRequired : BackupException("Backup needs its source password")

    /** The supplied source password does not open the backup (GCM tag mismatch). */
    class WrongPassword : BackupException("Wrong backup password")

    /** The vault is locked, so no portable key is available to write/read a backup. */
    class Locked : BackupException("Vault is locked")
}
