package com.itsluminous.cleartravel.core.data.backup

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.itsluminous.cleartravel.core.database.ClearTravelDatabase
import com.itsluminous.cleartravel.core.database.entity.toEntity
import com.itsluminous.cleartravel.core.database.entity.toModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton

/** Shared filename pattern for backup ZIPs (UI export suggestion + app storage). */
object BackupFileNames {
    private val FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")
    private val STORAGE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    /** `cleartravel-backup-YYYYMMDD-HHmm.zip` — the SAF CreateDocument suggestion. */
    fun suggestedExportName(
        at: Instant,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String = "cleartravel-backup-${FORMATTER.format(at.atZone(zone))}.zip"

    /** App-storage variant with seconds, so exports within a minute never collide. */
    internal fun appStorageName(
        at: Instant,
        zone: ZoneId,
    ): String = "cleartravel-backup-${STORAGE_FORMATTER.format(at.atZone(zone))}.zip"
}

/**
 * Room-backed [BackupManager] (ADR-015). Export dumps the FULL database (tombstones
 * included) through [BackupDao][com.itsluminous.cleartravel.core.database.dao.BackupDao],
 * bundling only local-only attachment files; import merges per-row last-write-wins
 * via the pure [BackupMerger] inside one Room transaction, writing winners through
 * the timestamp-preserving raw upserts.
 */
@Singleton
class DefaultBackupManager
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val database: ClearTravelDatabase,
        private val clock: Clock,
    ) : BackupManager {
        private val backupDao get() = database.backupDao()

        override suspend fun exportToUri(uri: Uri): ExportResult =
            withContext(Dispatchers.IO) {
                val result = writeBackup { out -> exportInto(out) }
                try {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        result.file.inputStream().use { it.copyTo(out) }
                    } ?: throw BackupException.Io()
                } catch (e: IOException) {
                    result.file.delete()
                    throw BackupException.Io(e)
                }
                // The same bytes become the newest app-storage copy (spec feature 6).
                promoteToAppStorage(result.file)
                result.exportResult
            }

        override suspend fun exportLatestToAppStorage(): ExportResult =
            withContext(Dispatchers.IO) {
                val result = writeBackup { out -> exportInto(out) }
                promoteToAppStorage(result.file)
                result.exportResult
            }

        override suspend fun importPreview(uri: Uri): ImportPreview =
            withContext(Dispatchers.IO) {
                withBackupZip(uri) { zip ->
                    val manifest = BackupCodec.readManifest(zip)
                    ImportPreview(
                        schemaVersion = manifest.schemaVersion,
                        appVersion = manifest.appVersion,
                        createdAt = Instant.ofEpochMilli(manifest.createdAt),
                        entityCounts = manifest.entityCounts,
                    )
                }
            }

        override suspend fun importApply(uri: Uri): MergeSummary =
            withContext(Dispatchers.IO) {
                withBackupZip(uri) { zip ->
                    val snapshot = BackupCodec.readSnapshot(zip)
                    database.withTransaction { mergeSnapshot(snapshot, zip) }
                }
            }

        override suspend fun latestLocalBackup(): LocalBackupInfo? =
            withContext(Dispatchers.IO) {
                val newest =
                    backupsDir()
                        .listFiles { file -> file.isFile && file.name.endsWith(".zip") }
                        ?.maxByOrNull { it.name }
                        ?: return@withContext null
                val createdAt =
                    try {
                        BackupCodec.openZip(newest).use { zip ->
                            Instant.ofEpochMilli(BackupCodec.readManifest(zip).createdAt)
                        }
                    } catch (e: BackupException) {
                        Instant.ofEpochMilli(newest.lastModified())
                    }
                LocalBackupInfo(fileName = newest.name, createdAt = createdAt, sizeBytes = newest.length())
            }

        // ---- Export internals ----

        private data class WrittenBackup(
            val file: File,
            val exportResult: ExportResult,
        )

        /** Builds the snapshot and writes the ZIP into a temp file in one pass. */
        private suspend fun writeBackup(write: suspend (File) -> ExportResult): WrittenBackup {
            val temp = File.createTempFile("cleartravel-backup", ".zip", context.cacheDir)
            try {
                val exportResult = write(temp)
                return WrittenBackup(temp, exportResult.copy(sizeBytes = temp.length()))
            } catch (e: Throwable) {
                temp.delete()
                throw e
            }
        }

        private suspend fun exportInto(target: File): ExportResult {
            val createdAt = clock.instant()
            val attachments = backupDao.dumpAttachments().map { it.toModel() }
            val bundledFiles =
                attachments
                    .filter { it.driveFileId == null && it.deletedAt == null }
                    .mapNotNull { attachment ->
                        val file = File(attachment.localPath)
                        if (file.isFile) attachment.id to file else null
                    }.toMap()
            val attachmentDtos = attachments.map { it.toDto(bundled = bundledFiles.containsKey(it.id)) }

            val trips = backupDao.dumpTrips().map { it.toModel().toDto() }
            val itineraryItems = backupDao.dumpItineraryItems().map { it.toModel().toDto() }
            val checklists = backupDao.dumpChecklists().map { it.toModel().toDto() }
            val checklistItems = backupDao.dumpChecklistItems().map { it.toModel().toDto() }
            val checklistPresets = backupDao.dumpChecklistPresets().map { it.toModel().toDto() }
            val checklistPresetItems = backupDao.dumpChecklistPresetItems().map { it.toModel().toDto() }
            val trainTickets = backupDao.dumpTrainTickets().map { it.toModel().toDto() }
            val trainPassengers = backupDao.dumpTrainPassengers().map { it.toModel().toDto() }
            val trainRouteStops = backupDao.dumpTrainRouteStops().map { it.toModel().toDto() }
            val flightJourneys = backupDao.dumpFlightJourneys().map { it.toModel().toDto() }

            val manifest =
                BackupManifest(
                    schemaVersion = BackupManifest.SCHEMA_VERSION,
                    appVersion = appVersion(),
                    createdAt = createdAt.toEpochMilli(),
                    entityCounts =
                        mapOf(
                            BackupEntries.KEY_TRIPS to trips.size,
                            BackupEntries.KEY_ITINERARY_ITEMS to itineraryItems.size,
                            BackupEntries.KEY_CHECKLISTS to checklists.size,
                            BackupEntries.KEY_CHECKLIST_ITEMS to checklistItems.size,
                            BackupEntries.KEY_CHECKLIST_PRESETS to checklistPresets.size,
                            BackupEntries.KEY_CHECKLIST_PRESET_ITEMS to checklistPresetItems.size,
                            BackupEntries.KEY_TRAIN_TICKETS to trainTickets.size,
                            BackupEntries.KEY_TRAIN_PASSENGERS to trainPassengers.size,
                            BackupEntries.KEY_TRAIN_ROUTE_STOPS to trainRouteStops.size,
                            BackupEntries.KEY_FLIGHT_JOURNEYS to flightJourneys.size,
                            BackupEntries.KEY_ATTACHMENTS to attachmentDtos.size,
                        ),
                )
            val snapshot =
                BackupSnapshot(
                    manifest = manifest,
                    trips = trips,
                    itineraryItems = itineraryItems,
                    checklists = checklists,
                    checklistItems = checklistItems,
                    checklistPresets = checklistPresets,
                    checklistPresetItems = checklistPresetItems,
                    trainTickets = trainTickets,
                    trainPassengers = trainPassengers,
                    trainRouteStops = trainRouteStops,
                    flightJourneys = flightJourneys,
                    attachments = attachmentDtos,
                )
            try {
                target.outputStream().use { out -> BackupCodec.writeZip(snapshot, bundledFiles, out) }
            } catch (e: IOException) {
                throw BackupException.Io(e)
            }
            return ExportResult(createdAt = createdAt, totalRows = manifest.totalRows, sizeBytes = 0)
        }

        /** Moves a written temp backup into `filesDir/backups`, pruning to the last N. */
        private fun promoteToAppStorage(source: File) {
            val dir = backupsDir().apply { mkdirs() }
            val target = File(dir, BackupFileNames.appStorageName(clock.instant(), ZoneId.systemDefault()))
            source.copyTo(target, overwrite = true)
            source.delete()
            dir
                .listFiles { file -> file.isFile && file.name.endsWith(".zip") }
                ?.sortedByDescending { it.name }
                ?.drop(MAX_LOCAL_BACKUPS)
                ?.forEach { it.delete() }
        }

        private fun backupsDir(): File = File(context.filesDir, BACKUPS_DIR_NAME)

        private fun appVersion(): String =
            try {
                context.packageManager
                    .getPackageInfo(context.packageName, 0)
                    .versionName
                    .orEmpty()
            } catch (e: Exception) {
                ""
            }

        // ---- Import internals ----

        /** Copies [uri] to a temp file (ZIP reading needs random access) and opens it. */
        private inline fun <T> withBackupZip(
            uri: Uri,
            block: (ZipFile) -> T,
        ): T {
            val temp = File.createTempFile("cleartravel-import", ".zip", context.cacheDir)
            try {
                try {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        temp.outputStream().use { input.copyTo(it) }
                    } ?: throw BackupException.Io()
                } catch (e: IOException) {
                    throw BackupException.Io(e)
                }
                return BackupCodec.openZip(temp).use(block)
            } finally {
                temp.delete()
            }
        }

        /** Runs the LWW merge for every entity type; MUST be called in a transaction. */
        private suspend fun mergeSnapshot(
            snapshot: BackupSnapshot,
            zip: ZipFile,
        ): MergeSummary {
            var summary = MergeSummary.ZERO

            BackupMerger
                .merge(backupDao.dumpTrips().map { it.toModel() }, snapshot.trips.map { it.toModel() })
                .also { plan ->
                    backupDao.upsertTrips(plan.toWrite.map { it.toEntity() })
                    summary += plan.summary
                }
            BackupMerger
                .merge(backupDao.dumpItineraryItems().map { it.toModel() }, snapshot.itineraryItems.map { it.toModel() })
                .also { plan ->
                    backupDao.upsertItineraryItems(plan.toWrite.map { it.toEntity() })
                    summary += plan.summary
                }
            BackupMerger
                .merge(backupDao.dumpChecklists().map { it.toModel() }, snapshot.checklists.map { it.toModel() })
                .also { plan ->
                    backupDao.upsertChecklists(plan.toWrite.map { it.toEntity() })
                    summary += plan.summary
                }
            BackupMerger
                .merge(backupDao.dumpChecklistItems().map { it.toModel() }, snapshot.checklistItems.map { it.toModel() })
                .also { plan ->
                    backupDao.upsertChecklistItems(plan.toWrite.map { it.toEntity() })
                    summary += plan.summary
                }
            BackupMerger
                .merge(backupDao.dumpChecklistPresets().map { it.toModel() }, snapshot.checklistPresets.map { it.toModel() })
                .also { plan ->
                    backupDao.upsertChecklistPresets(plan.toWrite.map { it.toEntity() })
                    summary += plan.summary
                }
            BackupMerger
                .merge(
                    backupDao.dumpChecklistPresetItems().map { it.toModel() },
                    snapshot.checklistPresetItems.map { it.toModel() },
                ).also { plan ->
                    backupDao.upsertChecklistPresetItems(plan.toWrite.map { it.toEntity() })
                    summary += plan.summary
                }
            BackupMerger
                .merge(backupDao.dumpTrainTickets().map { it.toModel() }, snapshot.trainTickets.map { it.toModel() })
                .also { plan ->
                    backupDao.upsertTrainTickets(plan.toWrite.map { it.toEntity() })
                    summary += plan.summary
                }
            BackupMerger
                .merge(backupDao.dumpTrainPassengers().map { it.toModel() }, snapshot.trainPassengers.map { it.toModel() })
                .also { plan ->
                    backupDao.upsertTrainPassengers(plan.toWrite.map { it.toEntity() })
                    summary += plan.summary
                }
            BackupMerger
                .merge(backupDao.dumpTrainRouteStops().map { it.toModel() }, snapshot.trainRouteStops.map { it.toModel() })
                .also { plan ->
                    backupDao.upsertTrainRouteStops(plan.toWrite.map { it.toEntity() })
                    summary += plan.summary
                }
            BackupMerger
                .merge(backupDao.dumpFlightJourneys().map { it.toModel() }, snapshot.flightJourneys.map { it.toModel() })
                .also { plan ->
                    backupDao.upsertFlightJourneys(plan.toWrite.map { it.toEntity() })
                    summary += plan.summary
                }

            // Attachments: winners with bundled bytes are restored into app storage
            // and their localPath re-pointed at the restored copy (paths are device-
            // local by nature; id/updatedAt/tombstone stay untouched). Drive-id-only
            // rows keep their original path — the Google milestone resolves them by
            // driveFileId on restore (documented seam, ADR-015).
            val bundledById = snapshot.attachments.filter { it.bundled }.associateBy { it.id }
            BackupMerger
                .merge(backupDao.dumpAttachments().map { it.toModel() }, snapshot.attachments.map { it.toModel() })
                .also { plan ->
                    val restored =
                        plan.toWrite.map { attachment ->
                            if (bundledById.containsKey(attachment.id)) {
                                val target = File(File(context.filesDir, ATTACHMENTS_DIR_NAME), attachment.id)
                                if (BackupCodec.extractAttachment(zip, attachment.id, target)) {
                                    attachment.copy(localPath = target.absolutePath)
                                } else {
                                    attachment
                                }
                            } else {
                                attachment
                            }
                        }
                    backupDao.upsertAttachments(restored.map { it.toEntity() })
                    summary += plan.summary
                }
            return summary
        }

        companion object {
            /** App-storage backups kept after pruning (spec: keep the latest N). */
            const val MAX_LOCAL_BACKUPS = 3
            internal const val BACKUPS_DIR_NAME = "backups"
            internal const val ATTACHMENTS_DIR_NAME = "attachments"
        }
    }
