package com.itsluminous.cleartravel.core.data.backup

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.itsluminous.cleartravel.core.data.repository.TravelDocumentStorage
import com.itsluminous.cleartravel.core.data.security.AppFileLayout
import com.itsluminous.cleartravel.core.database.ClearTravelDatabase
import com.itsluminous.cleartravel.core.database.entity.toEntity
import com.itsluminous.cleartravel.core.database.entity.toModel
import com.itsluminous.cleartravel.core.security.file.LocalFileCipher
import com.itsluminous.cleartravel.core.security.file.NotAnEnvelopeException
import com.itsluminous.cleartravel.core.security.file.PortableCipher
import com.itsluminous.cleartravel.core.security.vault.KeyVault
import com.itsluminous.cleartravel.core.security.vault.PortableKey
import com.itsluminous.cleartravel.core.security.vault.VaultLockedException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.ZipFile
import javax.crypto.AEADBadTagException
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
 *
 * ADR-031 (format v2): the ZIP is sealed in a [PortableCipher] envelope under the
 * vault's password-derived portable key — never the per-install DEK, so the file
 * restores on any install that knows the password. Bundled files are decrypted from
 * disk into the ZIP and re-encrypted on extraction. Import accepts v1 plain ZIPs
 * (pre-encryption exports), v2 envelopes from this vault silently, and v2 envelopes
 * from another password once the caller supplies it (the derived key is adopted for
 * the process, so preview → apply prompts once).
 */
@Singleton
class DefaultBackupManager
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val database: ClearTravelDatabase,
        private val clock: Clock,
        private val keyVault: KeyVault,
        private val fileCipher: LocalFileCipher,
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

        override suspend fun importPreview(
            uri: Uri,
            sourcePassword: CharArray?,
        ): ImportPreview =
            withContext(Dispatchers.IO) {
                withBackupZip(uri, sourcePassword) { zip ->
                    val manifest = BackupCodec.readManifest(zip)
                    ImportPreview(
                        schemaVersion = manifest.schemaVersion,
                        appVersion = manifest.appVersion,
                        createdAt = Instant.ofEpochMilli(manifest.createdAt),
                        entityCounts = manifest.entityCounts,
                    )
                }
            }

        override suspend fun importApply(
            uri: Uri,
            sourcePassword: CharArray?,
        ): MergeSummary =
            withContext(Dispatchers.IO) {
                withBackupZip(uri, sourcePassword) { zip ->
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
                        withPlainZip(newest, sourcePassword = null) { zip ->
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

            // Travel documents (ADR-027) follow the same bundling rule: local-only rows
            // whose file exists ride along under attachments/<documentId>.
            val documents = backupDao.dumpTravelDocuments().map { it.toModel() }
            val bundledDocumentFiles =
                documents
                    .filter { it.driveFileId == null && it.deletedAt == null }
                    .mapNotNull { document ->
                        val file = File(document.filePath)
                        if (file.isFile) document.id to file else null
                    }.toMap()
            val documentDtos = documents.map { it.toDto(bundled = bundledDocumentFiles.containsKey(it.id)) }

            val trips = backupDao.dumpTrips().map { it.toModel().toDto() }
            val itineraryItems = backupDao.dumpItineraryItems().map { it.toModel().toDto() }
            val checklists = backupDao.dumpChecklists().map { it.toModel().toDto() }
            val checklistItems = backupDao.dumpChecklistItems().map { it.toModel().toDto() }
            val checklistPresets = backupDao.dumpChecklistPresets().map { it.toModel().toDto() }
            val checklistPresetItems = backupDao.dumpChecklistPresetItems().map { it.toModel().toDto() }
            val trainTickets = backupDao.dumpTrainTickets().map { it.toModel().toDto() }
            val trainPassengers = backupDao.dumpTrainPassengers().map { it.toModel().toDto() }
            val trainRouteStops = backupDao.dumpTrainRouteStops().map { it.toModel().toDto() }
            val trainCoaches = backupDao.dumpTrainCoaches().map { it.toModel().toDto() }
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
                            BackupEntries.KEY_TRAIN_COACHES to trainCoaches.size,
                            BackupEntries.KEY_FLIGHT_JOURNEYS to flightJourneys.size,
                            BackupEntries.KEY_ATTACHMENTS to attachmentDtos.size,
                            BackupEntries.KEY_TRAVEL_DOCUMENTS to documentDtos.size,
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
                    trainCoaches = trainCoaches,
                    flightJourneys = flightJourneys,
                    attachments = attachmentDtos,
                    travelDocuments = documentDtos,
                )
            val portableKey = portableKeyOrThrow()
            try {
                // v2: the whole ZIP is sealed in the portable envelope; bundled files
                // are decrypted from disk so the payload is plaintext inside it.
                PortableCipher.encryptingStream(target.outputStream().buffered(), portableKey).use { out ->
                    BackupCodec.writeZip(snapshot, bundledFiles + bundledDocumentFiles, out) { file -> fileCipher.openDecrypted(file) }
                }
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

        private fun backupsDir(): File = AppFileLayout.backups(context.filesDir)

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
        private suspend fun <T> withBackupZip(
            uri: Uri,
            sourcePassword: CharArray?,
            block: suspend (ZipFile) -> T,
        ): T {
            val temp = File.createTempFile("cleartravel-import", ".bin", context.cacheDir)
            try {
                try {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        temp.outputStream().use { input.copyTo(it) }
                    } ?: throw BackupException.Io()
                } catch (e: IOException) {
                    throw BackupException.Io(e)
                }
                return withPlainZip(temp, sourcePassword, block)
            } finally {
                temp.delete()
            }
        }

        /**
         * Opens [file] as a plain ZIP: a v2 envelope is decrypted into a second temp
         * file first (own key → adopted key → [sourcePassword] → [BackupException.PasswordRequired]);
         * anything else is treated as a v1 plain ZIP.
         */
        private suspend fun <T> withPlainZip(
            file: File,
            sourcePassword: CharArray?,
            block: suspend (ZipFile) -> T,
        ): T {
            if (!PortableCipher.isEnvelope(file)) return BackupCodec.openZip(file).use { block(it) }
            val plain = File.createTempFile("cleartravel-import", ".zip", context.cacheDir)
            try {
                decryptEnvelope(file, plain, sourcePassword)
                return BackupCodec.openZip(plain).use { block(it) }
            } finally {
                plain.delete()
            }
        }

        private suspend fun decryptEnvelope(
            envelope: File,
            plain: File,
            sourcePassword: CharArray?,
        ) {
            val header =
                try {
                    PortableCipher.readHeader(envelope)
                } catch (e: NotAnEnvelopeException) {
                    throw BackupException.CorruptedBackup(e)
                } catch (e: IOException) {
                    throw BackupException.CorruptedBackup(e)
                }
            // An explicitly supplied password always wins over a cached key (a previous
            // wrong attempt must not shadow the right one); a key is only remembered
            // once it has actually opened the envelope.
            val known = keyVault.portableKeyFor(header.salt, header.iterations)
            val key: PortableKey =
                sourcePassword?.let { keyVault.derivePortableKey(it, header.salt, header.iterations) }
                    ?: known
                    ?: throw BackupException.PasswordRequired()
            try {
                PortableCipher.openDecrypted(envelope, key).use { input ->
                    plain.outputStream().buffered().use { out -> input.copyTo(out) }
                }
            } catch (e: AEADBadTagException) {
                throw BackupException.WrongPassword()
            } catch (e: IOException) {
                throw BackupException.CorruptedBackup(e)
            }
            if (key !== known) keyVault.adoptPortableKey(key)
        }

        private fun portableKeyOrThrow(): PortableKey =
            try {
                keyVault.portableKey()
            } catch (e: VaultLockedException) {
                throw BackupException.Locked()
            }

        /** Extraction sink: restored bytes land CTEF-encrypted like every stored file. */
        private val encryptingExtract: (InputStream, File) -> Unit = { input, target -> fileCipher.encryptTo(input, target) }

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
                .merge(backupDao.dumpTrainCoaches().map { it.toModel() }, snapshot.trainCoaches.map { it.toModel() })
                .also { plan ->
                    backupDao.upsertTrainCoaches(plan.toWrite.map { it.toEntity() })
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
                                val target = File(AppFileLayout.attachments(context.filesDir), attachment.id)
                                if (BackupCodec.extractAttachment(zip, attachment.id, target, encryptingExtract)) {
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

            // Travel documents (ADR-027): same restore rule, into filesDir/documents/
            // keeping the original extension (the viewer keys PDF rendering off it).
            val bundledDocuments = snapshot.travelDocuments.filter { it.bundled }.associateBy { it.id }
            BackupMerger
                .merge(backupDao.dumpTravelDocuments().map { it.toModel() }, snapshot.travelDocuments.map { it.toModel() })
                .also { plan ->
                    val restored =
                        plan.toWrite.map { document ->
                            if (bundledDocuments.containsKey(document.id)) {
                                val fileName = TravelDocumentStorage.fileName(document.id, File(document.filePath).extension)
                                val target = File(TravelDocumentStorage.directory(context.filesDir), fileName)
                                if (BackupCodec.extractAttachment(zip, document.id, target, encryptingExtract)) {
                                    document.copy(filePath = target.absolutePath)
                                } else {
                                    document
                                }
                            } else {
                                document
                            }
                        }
                    backupDao.upsertTravelDocuments(restored.map { it.toEntity() })
                    summary += plan.summary
                }
            return summary
        }

        companion object {
            /** App-storage backups kept after pruning (spec: keep the latest N). */
            const val MAX_LOCAL_BACKUPS = 3
            internal const val BACKUPS_DIR_NAME = AppFileLayout.BACKUPS_DIR
            internal const val ATTACHMENTS_DIR_NAME = AppFileLayout.ATTACHMENTS_DIR
        }
    }
