package com.itsluminous.cleartravel.core.data.backup

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.data.repository.TravelDocumentStorage
import com.itsluminous.cleartravel.core.database.ClearTravelDatabase
import com.itsluminous.cleartravel.core.database.entity.toEntity
import com.itsluminous.cleartravel.core.database.entity.toModel
import com.itsluminous.cleartravel.core.model.TravelDocumentType
import com.itsluminous.cleartravel.core.testing.Fixtures
import com.itsluminous.cleartravel.core.testing.inMemoryDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Clock
import java.time.ZoneOffset
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * End-to-end backup engine tests (Robolectric — Room + real ZIP + real files):
 * export → wipe → import round trip, LWW merge in both directions, tombstone
 * replication, idempotence, attachment bundling rules, version gate, corruption.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DefaultBackupManagerTest {
    private lateinit var context: Context
    private lateinit var db: ClearTravelDatabase
    private lateinit var manager: DefaultBackupManager
    private val clock: Clock = Clock.fixed(Fixtures.NOW.plusSeconds(3600), ZoneOffset.UTC)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = inMemoryDatabase(context)
        manager = DefaultBackupManager(context, db, clock)
    }

    @After
    fun tearDown() {
        db.close()
        File(context.filesDir, DefaultBackupManager.BACKUPS_DIR_NAME).deleteRecursively()
        File(context.filesDir, DefaultBackupManager.ATTACHMENTS_DIR_NAME).deleteRecursively()
    }

    private fun exportFileUri(name: String = "export.zip"): Uri = Uri.fromFile(File(context.cacheDir, name))

    /** Seeds one row of every entity type, timestamps preserved, tombstones included. */
    private suspend fun seedAllEntityTypes(): Map<String, Any> {
        val dao = db.backupDao()
        val trip = Fixtures.trip()
        val tombstonedTrip = Fixtures.trip(deletedAt = Fixtures.NOW)
        val item = Fixtures.itineraryItem(tripId = trip.id)
        val checklist = Fixtures.checklist(tripId = trip.id)
        val checklistItem = Fixtures.checklistItem(checklistId = checklist.id, checked = true)
        val preset = Fixtures.checklistPreset(builtIn = true)
        val presetItem = Fixtures.checklistPresetItem(presetId = preset.id)
        val ticket = Fixtures.trainTicket(lastFetchedAt = Fixtures.NOW)
        val passenger = Fixtures.trainPassenger(ticketId = ticket.id)
        val stop = Fixtures.trainRouteStop(ticketId = ticket.id)
        val coach = Fixtures.trainCoach(ticketId = ticket.id, code = "EN")
        val flight = Fixtures.flightJourney(boardingPassPath = "/nonexistent/bp.pdf")
        val attachment = Fixtures.attachment(ownerId = ticket.id, localPath = "/nonexistent/file.pdf")
        val document = Fixtures.travelDocument(filePath = "/nonexistent/passport.jpg", expiryDate = Fixtures.TODAY.plusYears(9))
        dao.upsertTrips(listOf(trip.toEntity(), tombstonedTrip.toEntity()))
        dao.upsertItineraryItems(listOf(item.toEntity()))
        dao.upsertChecklists(listOf(checklist.toEntity()))
        dao.upsertChecklistItems(listOf(checklistItem.toEntity()))
        dao.upsertChecklistPresets(listOf(preset.toEntity()))
        dao.upsertChecklistPresetItems(listOf(presetItem.toEntity()))
        dao.upsertTrainTickets(listOf(ticket.toEntity()))
        dao.upsertTrainPassengers(listOf(passenger.toEntity()))
        dao.upsertTrainRouteStops(listOf(stop.toEntity()))
        dao.upsertTrainCoaches(listOf(coach.toEntity()))
        dao.upsertFlightJourneys(listOf(flight.toEntity()))
        dao.upsertAttachments(listOf(attachment.toEntity()))
        dao.upsertTravelDocuments(listOf(document.toEntity()))
        return mapOf(
            "trip" to trip,
            "tombstonedTrip" to tombstonedTrip,
            "item" to item,
            "checklist" to checklist,
            "checklistItem" to checklistItem,
            "preset" to preset,
            "presetItem" to presetItem,
            "ticket" to ticket,
            "passenger" to passenger,
            "stop" to stop,
            "coach" to coach,
            "flight" to flight,
            "attachment" to attachment,
            "document" to document,
        )
    }

    @Test
    fun `round trip - export then import into wiped db restores every entity type verbatim`() =
        runTest {
            val seeded = seedAllEntityTypes()
            val uri = exportFileUri()
            val export = manager.exportToUri(uri)
            assertThat(export.totalRows).isEqualTo(14)

            // "Wipe": a brand-new empty database.
            val freshDb = inMemoryDatabase<ClearTravelDatabase>(context)
            val freshManager = DefaultBackupManager(context, freshDb, clock)
            val summary = freshManager.importApply(uri)

            assertThat(summary).isEqualTo(MergeSummary(inserted = 14, updated = 0, skipped = 0))
            val dao = freshDb.backupDao()
            assertThat(dao.dumpTrips().map { it.toModel() })
                .containsExactly(seeded["trip"], seeded["tombstonedTrip"])
            assertThat(dao.dumpItineraryItems().single().toModel()).isEqualTo(seeded["item"])
            assertThat(dao.dumpChecklists().single().toModel()).isEqualTo(seeded["checklist"])
            assertThat(dao.dumpChecklistItems().single().toModel()).isEqualTo(seeded["checklistItem"])
            assertThat(dao.dumpChecklistPresets().single().toModel()).isEqualTo(seeded["preset"])
            assertThat(dao.dumpChecklistPresetItems().single().toModel()).isEqualTo(seeded["presetItem"])
            assertThat(dao.dumpTrainTickets().single().toModel()).isEqualTo(seeded["ticket"])
            assertThat(dao.dumpTrainPassengers().single().toModel()).isEqualTo(seeded["passenger"])
            assertThat(dao.dumpTrainRouteStops().single().toModel()).isEqualTo(seeded["stop"])
            assertThat(dao.dumpTrainCoaches().single().toModel()).isEqualTo(seeded["coach"])
            assertThat(dao.dumpFlightJourneys().single().toModel()).isEqualTo(seeded["flight"])
            assertThat(dao.dumpAttachments().single().toModel()).isEqualTo(seeded["attachment"])
            assertThat(dao.dumpTravelDocuments().single().toModel()).isEqualTo(seeded["document"])
            freshDb.close()
        }

    @Test
    fun `import is idempotent - second import of the same file changes nothing`() =
        runTest {
            seedAllEntityTypes()
            val uri = exportFileUri()
            manager.exportToUri(uri)

            val first = manager.importApply(uri)
            val second = manager.importApply(uri)

            assertThat(first.inserted).isEqualTo(0)
            assertThat(first.skipped).isEqualTo(14)
            assertThat(second).isEqualTo(MergeSummary(inserted = 0, updated = 0, skipped = 14))
        }

    @Test
    fun `lww - newer backup row overwrites older local row entirely`() =
        runTest {
            val old = Fixtures.trip(id = Fixtures.FIXED_ID, name = "Backup newer", updatedAt = Fixtures.NOW.plusSeconds(99))
            db.backupDao().upsertTrips(listOf(old.toEntity()))
            val uri = exportFileUri()
            manager.exportToUri(uri)

            // Local edit that is OLDER than the backup row.
            db.backupDao().upsertTrips(listOf(old.copy(name = "Local older", updatedAt = Fixtures.NOW).toEntity()))
            val summary = manager.importApply(uri)

            assertThat(summary).isEqualTo(MergeSummary(inserted = 0, updated = 1, skipped = 0))
            val restored =
                db
                    .backupDao()
                    .dumpTrips()
                    .single()
                    .toModel()
            assertThat(restored.name).isEqualTo("Backup newer")
            assertThat(restored.updatedAt).isEqualTo(Fixtures.NOW.plusSeconds(99))
        }

    @Test
    fun `lww - newer local row survives an older backup row`() =
        runTest {
            val old = Fixtures.trip(id = Fixtures.FIXED_ID, name = "Old", updatedAt = Fixtures.NOW)
            db.backupDao().upsertTrips(listOf(old.toEntity()))
            val uri = exportFileUri()
            manager.exportToUri(uri)

            val newerLocal = old.copy(name = "Newer local", updatedAt = Fixtures.NOW.plusSeconds(500))
            db.backupDao().upsertTrips(listOf(newerLocal.toEntity()))
            val summary = manager.importApply(uri)

            assertThat(summary).isEqualTo(MergeSummary(inserted = 0, updated = 0, skipped = 1))
            assertThat(
                db
                    .backupDao()
                    .dumpTrips()
                    .single()
                    .toModel(),
            ).isEqualTo(newerLocal)
        }

    @Test
    fun `tombstone replication - newer backup tombstone deletes local live row`() =
        runTest {
            val tombstone =
                Fixtures.trip(
                    id = Fixtures.FIXED_ID,
                    updatedAt = Fixtures.NOW.plusSeconds(60),
                    deletedAt = Fixtures.NOW.plusSeconds(60),
                )
            db.backupDao().upsertTrips(listOf(tombstone.toEntity()))
            val uri = exportFileUri()
            manager.exportToUri(uri)

            // Same row live locally, but older.
            db.backupDao().upsertTrips(listOf(tombstone.copy(updatedAt = Fixtures.NOW, deletedAt = null).toEntity()))
            manager.importApply(uri)

            val row =
                db
                    .backupDao()
                    .dumpTrips()
                    .single()
                    .toModel()
            assertThat(row.deletedAt).isEqualTo(Fixtures.NOW.plusSeconds(60))
            assertThat(db.tripDao().getById(Fixtures.FIXED_ID)).isNull()
        }

    @Test
    fun `local-only rows survive an import untouched`() =
        runTest {
            val uri = exportFileUri()
            manager.exportToUri(uri) // empty backup

            val localOnly = Fixtures.trip(name = "Local only")
            db.backupDao().upsertTrips(listOf(localOnly.toEntity()))
            val summary = manager.importApply(uri)

            assertThat(summary).isEqualTo(MergeSummary.ZERO)
            assertThat(
                db
                    .backupDao()
                    .dumpTrips()
                    .single()
                    .toModel(),
            ).isEqualTo(localOnly)
        }

    @Test
    fun `attachments - local-only file is bundled and restored to app storage with same bytes`() =
        runTest {
            val sourceFile = File(context.cacheDir, "boarding.pdf").apply { writeBytes(byteArrayOf(1, 2, 3, 4, 5)) }
            val attachment = Fixtures.attachment(localPath = sourceFile.absolutePath, driveFileId = null)
            db.backupDao().upsertAttachments(listOf(attachment.toEntity()))
            val uri = exportFileUri()
            manager.exportToUri(uri)

            // ZIP contains the bundled bytes.
            ZipFile(File(context.cacheDir, "export.zip")).use { zip ->
                val entry = zip.getEntry(BackupEntries.attachmentEntry(attachment.id))
                assertThat(entry).isNotNull()
                assertThat(zip.getInputStream(entry).readBytes()).isEqualTo(byteArrayOf(1, 2, 3, 4, 5))
            }

            val freshDb = inMemoryDatabase<ClearTravelDatabase>(context)
            val freshManager = DefaultBackupManager(context, freshDb, clock)
            freshManager.importApply(uri)

            val restored =
                freshDb
                    .backupDao()
                    .dumpAttachments()
                    .single()
                    .toModel()
            assertThat(restored.id).isEqualTo(attachment.id)
            assertThat(restored.updatedAt).isEqualTo(attachment.updatedAt)
            assertThat(restored.localPath).isNotEqualTo(sourceFile.absolutePath)
            assertThat(File(restored.localPath).readBytes()).isEqualTo(byteArrayOf(1, 2, 3, 4, 5))
            freshDb.close()
        }

    @Test
    fun `attachments - drive-backed file is NOT bundled and row keeps its drive id`() =
        runTest {
            val sourceFile = File(context.cacheDir, "uploaded.pdf").apply { writeBytes(byteArrayOf(9, 9)) }
            val attachment = Fixtures.attachment(localPath = sourceFile.absolutePath, driveFileId = "drive-42")
            db.backupDao().upsertAttachments(listOf(attachment.toEntity()))
            val uri = exportFileUri()
            manager.exportToUri(uri)

            ZipFile(File(context.cacheDir, "export.zip")).use { zip ->
                assertThat(zip.getEntry(BackupEntries.attachmentEntry(attachment.id))).isNull()
            }

            val freshDb = inMemoryDatabase<ClearTravelDatabase>(context)
            DefaultBackupManager(context, freshDb, clock).importApply(uri)

            // Restored as-is: drive id kept, path resolution deferred to the Google
            // milestone (documented seam, ADR-015).
            val restored =
                freshDb
                    .backupDao()
                    .dumpAttachments()
                    .single()
                    .toModel()
            assertThat(restored.driveFileId).isEqualTo("drive-42")
            assertThat(restored.localPath).isEqualTo(sourceFile.absolutePath)
            freshDb.close()
        }

    @Test
    fun `travel documents - local file is bundled and restored under filesDir documents keeping its extension`() =
        runTest {
            val sourceFile = File(context.cacheDir, "passport-scan.pdf").apply { writeBytes(byteArrayOf(7, 6, 5, 4)) }
            val document =
                Fixtures.travelDocument(
                    type = TravelDocumentType.PASSPORT,
                    filePath = sourceFile.absolutePath,
                    mimeType = "application/pdf",
                    expiryDate = Fixtures.TODAY.plusYears(8),
                )
            db.backupDao().upsertTravelDocuments(listOf(document.toEntity()))
            val uri = exportFileUri()
            manager.exportToUri(uri)

            ZipFile(File(context.cacheDir, "export.zip")).use { zip ->
                assertThat(zip.getEntry(BackupEntries.TRAVEL_DOCUMENTS)).isNotNull()
                val entry = zip.getEntry(BackupEntries.attachmentEntry(document.id))
                assertThat(entry).isNotNull()
                assertThat(zip.getInputStream(entry).readBytes()).isEqualTo(byteArrayOf(7, 6, 5, 4))
            }

            val freshDb = inMemoryDatabase<ClearTravelDatabase>(context)
            DefaultBackupManager(context, freshDb, clock).importApply(uri)

            val restored =
                freshDb
                    .backupDao()
                    .dumpTravelDocuments()
                    .single()
                    .toModel()
            assertThat(restored.id).isEqualTo(document.id)
            assertThat(restored.updatedAt).isEqualTo(document.updatedAt)
            assertThat(restored.expiryDate).isEqualTo(document.expiryDate)
            assertThat(restored.type).isEqualTo(TravelDocumentType.PASSPORT)
            val restoredFile = File(restored.filePath)
            assertThat(restoredFile.parentFile).isEqualTo(TravelDocumentStorage.directory(context.filesDir))
            assertThat(restoredFile.name).isEqualTo("${document.id}.pdf")
            assertThat(restoredFile.readBytes()).isEqualTo(byteArrayOf(7, 6, 5, 4))
            freshDb.close()
        }

    @Test
    fun `travel documents - missing file is exported as row only and restored un-bundled`() =
        runTest {
            val document = Fixtures.travelDocument(filePath = "/nonexistent/visa.jpg")
            db.backupDao().upsertTravelDocuments(listOf(document.toEntity()))
            val uri = exportFileUri()
            manager.exportToUri(uri)

            ZipFile(File(context.cacheDir, "export.zip")).use { zip ->
                assertThat(zip.getEntry(BackupEntries.attachmentEntry(document.id))).isNull()
            }

            val freshDb = inMemoryDatabase<ClearTravelDatabase>(context)
            DefaultBackupManager(context, freshDb, clock).importApply(uri)
            val restored =
                freshDb
                    .backupDao()
                    .dumpTravelDocuments()
                    .single()
                    .toModel()
            assertThat(restored).isEqualTo(document)
            freshDb.close()
        }

    @Test
    fun `pre-ADR-027 backup without a travel_documents entry imports with zero documents`() =
        runTest {
            seedAllEntityTypes()
            val full = File(context.cacheDir, "full.zip")
            manager.exportToUri(Uri.fromFile(full))
            val legacy = File(context.cacheDir, "legacy.zip")
            ZipFile(full).use { source ->
                ZipOutputStream(legacy.outputStream()).use { zip ->
                    for (entry in source.entries().asSequence()) {
                        if (entry.name == BackupEntries.TRAVEL_DOCUMENTS) continue
                        zip.putNextEntry(ZipEntry(entry.name))
                        source.getInputStream(entry).use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
            }

            val freshDb = inMemoryDatabase<ClearTravelDatabase>(context)
            val summary = DefaultBackupManager(context, freshDb, clock).importApply(Uri.fromFile(legacy))

            assertThat(summary).isEqualTo(MergeSummary(inserted = 13, updated = 0, skipped = 0))
            assertThat(freshDb.backupDao().dumpTravelDocuments()).isEmpty()
            assertThat(freshDb.backupDao().dumpTrainCoaches()).hasSize(1)
            freshDb.close()
        }

    @Test
    fun `travel documents merge last-write-wins and replicate tombstones`() =
        runTest {
            val seeded = seedAllEntityTypes()
            val exported = seeded["document"] as com.itsluminous.cleartravel.core.model.TravelDocument
            val uri = exportFileUri()
            manager.exportToUri(uri)

            // Local rename is NEWER than the backup copy → survives the import.
            val newerLocal = exported.copy(name = "Passport (renewed)", updatedAt = Fixtures.NOW.plusSeconds(10))
            db.backupDao().upsertTravelDocuments(listOf(newerLocal.toEntity()))
            assertThat(manager.importApply(uri).updated).isEqualTo(0)
            assertThat(
                db
                    .backupDao()
                    .dumpTravelDocuments()
                    .single()
                    .toModel()
                    .name,
            ).isEqualTo("Passport (renewed)")

            // A NEWER tombstone in a second backup deletes the local live row.
            val tombstoned = newerLocal.copy(updatedAt = Fixtures.NOW.plusSeconds(20), deletedAt = Fixtures.NOW.plusSeconds(20))
            val otherDb = inMemoryDatabase<ClearTravelDatabase>(context)
            otherDb.backupDao().upsertTravelDocuments(listOf(tombstoned.toEntity()))
            val tombstoneUri = exportFileUri("tombstone.zip")
            DefaultBackupManager(context, otherDb, clock).exportToUri(tombstoneUri)
            otherDb.close()

            val summary = manager.importApply(tombstoneUri)
            assertThat(summary.updated).isEqualTo(1)
            val local =
                db
                    .backupDao()
                    .dumpTravelDocuments()
                    .single()
                    .toModel()
            assertThat(local.deletedAt).isEqualTo(tombstoned.deletedAt)
            assertThat(db.travelDocumentDao().observeAll().first()).isEmpty()
        }

    @Test
    fun `version gate - newer schemaVersion is rejected with a typed error`() =
        runTest {
            val file = File(context.cacheDir, "future.zip")
            ZipOutputStream(file.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry(BackupEntries.MANIFEST))
                val manifest =
                    BackupManifest(
                        schemaVersion = 2,
                        appVersion = "9.9.9",
                        createdAt = Fixtures.NOW.toEpochMilli(),
                        entityCounts = emptyMap(),
                    )
                zip.write(BackupCodec.json.encodeToString(manifest).encodeToByteArray())
                zip.closeEntry()
            }

            val previewError =
                assertThrows(BackupException.UnsupportedSchemaVersion::class.java) {
                    kotlinx.coroutines.runBlocking { manager.importPreview(Uri.fromFile(file)) }
                }
            assertThat(previewError.found).isEqualTo(2)
            assertThrows(BackupException.UnsupportedSchemaVersion::class.java) {
                kotlinx.coroutines.runBlocking { manager.importApply(Uri.fromFile(file)) }
            }
        }

    @Test
    fun `pre-ADR-022 backup without a train_coaches entry imports with zero coaches`() =
        runTest {
            // Simulate a backup written by an app version predating the coaches
            // table: export normally, then strip the coaches entry from the ZIP.
            seedAllEntityTypes()
            val full = File(context.cacheDir, "full.zip")
            manager.exportToUri(Uri.fromFile(full))
            val legacy = File(context.cacheDir, "legacy.zip")
            ZipFile(full).use { source ->
                ZipOutputStream(legacy.outputStream()).use { zip ->
                    for (entry in source.entries().asSequence()) {
                        if (entry.name == BackupEntries.TRAIN_COACHES) continue
                        zip.putNextEntry(ZipEntry(entry.name))
                        source.getInputStream(entry).use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
            }

            val freshDb = inMemoryDatabase<ClearTravelDatabase>(context)
            val freshManager = DefaultBackupManager(context, freshDb, clock)
            val summary = freshManager.importApply(Uri.fromFile(legacy))

            assertThat(summary).isEqualTo(MergeSummary(inserted = 13, updated = 0, skipped = 0))
            assertThat(freshDb.backupDao().dumpTrainCoaches()).isEmpty()
            assertThat(freshDb.backupDao().dumpTrainRouteStops()).hasSize(1)
            freshDb.close()
        }

    @Test
    fun `coaches merge last-write-wins like every other entity`() =
        runTest {
            val seeded = seedAllEntityTypes()
            val ticket = seeded["ticket"] as com.itsluminous.cleartravel.core.model.TrainTicket
            val uri = exportFileUri()
            manager.exportToUri(uri)

            // Local now has a NEWER coach composition for the same ticket plus a
            // renamed copy of the exported coach (same id, older stamp in the backup).
            val exported = seeded["coach"] as com.itsluminous.cleartravel.core.model.TrainCoach
            val newerLocal = exported.copy(code = "LOCO", updatedAt = Fixtures.NOW.plusSeconds(10))
            db.backupDao().upsertTrainCoaches(listOf(newerLocal.toEntity()))

            val summary = manager.importApply(uri)

            assertThat(summary.updated).isEqualTo(0)
            val coaches = db.backupDao().dumpTrainCoaches().map { it.toModel() }
            assertThat(coaches.single { it.ticketId == ticket.id }.code).isEqualTo("LOCO")
        }

    @Test
    fun `corrupted file - not a zip is a typed error`() =
        runTest {
            val file = File(context.cacheDir, "garbage.zip").apply { writeText("this is not a zip archive") }

            assertThrows(BackupException.CorruptedBackup::class.java) {
                kotlinx.coroutines.runBlocking { manager.importPreview(Uri.fromFile(file)) }
            }
            assertThrows(BackupException.CorruptedBackup::class.java) {
                kotlinx.coroutines.runBlocking { manager.importApply(Uri.fromFile(file)) }
            }
        }

    @Test
    fun `corrupted file - zip without manifest is a typed error`() =
        runTest {
            val file = File(context.cacheDir, "nomanifest.zip")
            ZipOutputStream(file.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("random.txt"))
                zip.write("hello".encodeToByteArray())
                zip.closeEntry()
            }

            assertThrows(BackupException.CorruptedBackup::class.java) {
                kotlinx.coroutines.runBlocking { manager.importPreview(Uri.fromFile(file)) }
            }
        }

    @Test
    fun `preview reports manifest date and per-entity counts without touching data`() =
        runTest {
            seedAllEntityTypes()
            val uri = exportFileUri()
            manager.exportToUri(uri)

            val freshDb = inMemoryDatabase<ClearTravelDatabase>(context)
            val freshManager = DefaultBackupManager(context, freshDb, clock)
            val preview = freshManager.importPreview(uri)

            assertThat(preview.schemaVersion).isEqualTo(1)
            assertThat(preview.createdAt).isEqualTo(clock.instant())
            assertThat(preview.totalRows).isEqualTo(14)
            assertThat(preview.entityCounts[BackupEntries.KEY_TRIPS]).isEqualTo(2)
            assertThat(preview.entityCounts[BackupEntries.KEY_TRAIN_TICKETS]).isEqualTo(1)
            // Preview must not import anything.
            assertThat(freshDb.backupDao().dumpTrips()).isEmpty()
            freshDb.close()
        }

    @Test
    fun `app storage keeps the latest backup and prunes to the newest three`() =
        runTest {
            var tick = 0L
            repeat(5) {
                val tickedClock = Clock.fixed(Fixtures.NOW.plusSeconds(3600 + tick), ZoneOffset.UTC)
                DefaultBackupManager(context, db, tickedClock).exportLatestToAppStorage()
                tick += 61 // distinct HHmmss names
            }

            val dir = File(context.filesDir, DefaultBackupManager.BACKUPS_DIR_NAME)
            val files = dir.listFiles()!!.map { it.name }.sorted()
            assertThat(files).hasSize(DefaultBackupManager.MAX_LOCAL_BACKUPS)

            val info = manager.latestLocalBackup()
            assertThat(info).isNotNull()
            assertThat(info!!.fileName).isEqualTo(files.max())
            assertThat(info.sizeBytes).isGreaterThan(0)
        }

    @Test
    fun `exportToUri also refreshes the app-storage copy`() =
        runTest {
            seedAllEntityTypes()
            assertThat(manager.latestLocalBackup()).isNull()

            manager.exportToUri(exportFileUri())

            val info = manager.latestLocalBackup()
            assertThat(info).isNotNull()
            assertThat(info!!.createdAt).isEqualTo(clock.instant())
        }
}
