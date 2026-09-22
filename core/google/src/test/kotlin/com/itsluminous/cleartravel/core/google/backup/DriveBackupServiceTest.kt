package com.itsluminous.cleartravel.core.google.backup

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.data.backup.BackupManager
import com.itsluminous.cleartravel.core.data.backup.ExportResult
import com.itsluminous.cleartravel.core.data.backup.ImportPreview
import com.itsluminous.cleartravel.core.data.backup.LocalBackupInfo
import com.itsluminous.cleartravel.core.data.backup.MergeSummary
import com.itsluminous.cleartravel.core.data.repository.ChecklistRepository
import com.itsluminous.cleartravel.core.google.FakeFlightRepository
import com.itsluminous.cleartravel.core.google.FakeGoogleLinkStore
import com.itsluminous.cleartravel.core.google.FakeTrainRepository
import com.itsluminous.cleartravel.core.google.FakeTripRepository
import com.itsluminous.cleartravel.core.google.GoogleScopes
import com.itsluminous.cleartravel.core.google.auth.GoogleLinkSnapshot
import com.itsluminous.cleartravel.core.google.drive.DriveFolderResolver
import com.itsluminous.cleartravel.core.google.drive.FakeDriveClient
import com.itsluminous.cleartravel.core.model.Checklist
import com.itsluminous.cleartravel.core.model.ChecklistItem
import com.itsluminous.cleartravel.core.model.FlightJourney
import com.itsluminous.cleartravel.core.model.Trip
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant

/** Minimal [BackupManager] fake — only `latestLocalBackup` matters for the upload. */
class FakeBackupManager(
    var latest: LocalBackupInfo? = null,
) : BackupManager {
    var importApplied: Uri? = null
    var importPreviewResult: ImportPreview? = null

    override suspend fun exportToUri(uri: Uri): ExportResult = ExportResult(Instant.EPOCH, 0, 0)

    override suspend fun exportLatestToAppStorage(): ExportResult = ExportResult(Instant.EPOCH, 0, 0)

    override suspend fun importPreview(
        uri: Uri,
        sourcePassword: CharArray?,
    ): ImportPreview = importPreviewResult ?: ImportPreview(1, "0.1.0", Instant.EPOCH, emptyMap())

    override suspend fun importApply(
        uri: Uri,
        sourcePassword: CharArray?,
    ): MergeSummary {
        importApplied = uri
        return MergeSummary(1, 0, 0)
    }

    override suspend fun latestLocalBackup(): LocalBackupInfo? = latest
}

/** Minimal in-memory [ChecklistRepository] — only `observeChecklists` is used here. */
class FakeChecklistRepository : ChecklistRepository {
    val checklists = MutableStateFlow<List<Checklist>>(emptyList())

    override fun observeChecklists(): Flow<List<Checklist>> = checklists

    override fun observeChecklistsForTrip(tripId: String): Flow<List<Checklist>> =
        checklists.map { list -> list.filter { it.tripId == tripId } }

    override fun observeChecklist(id: String): Flow<Checklist?> = checklists.map { list -> list.firstOrNull { it.id == id } }

    override fun observeItems(checklistId: String): Flow<List<ChecklistItem>> = MutableStateFlow(emptyList())

    override suspend fun save(checklist: Checklist): Checklist {
        checklists.value = checklists.value.filterNot { it.id == checklist.id } + checklist
        return checklist
    }

    override suspend fun saveItem(item: ChecklistItem): ChecklistItem = item

    override suspend fun saveItems(items: List<ChecklistItem>): List<ChecklistItem> = items

    override suspend fun setItemChecked(
        itemId: String,
        checked: Boolean,
    ) = Unit

    override suspend fun appendPreset(
        checklistId: String,
        presetId: String,
    ): List<ChecklistItem> = emptyList()

    override suspend fun deleteChecklist(id: String) {
        checklists.value = checklists.value.filterNot { it.id == id }
    }

    override suspend fun deleteItem(id: String) = Unit
}

class DriveBackupServiceTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var linkStore: FakeGoogleLinkStore
    private lateinit var drive: FakeDriveClient
    private lateinit var backupManager: FakeBackupManager
    private lateinit var backupsDir: File

    @Before
    fun setUp() {
        linkStore =
            FakeGoogleLinkStore(
                GoogleLinkSnapshot(
                    email = "traveler@example.com",
                    grantedScopes = setOf(GoogleScopes.DRIVE_FILE),
                    driveBackupEnabled = true,
                ),
            )
        drive = FakeDriveClient()
        backupManager = FakeBackupManager()
        backupsDir = tmp.newFolder("backups")
    }

    private fun service(): DriveBackupService =
        DefaultDriveBackupService(
            linkStore = linkStore,
            backupManager = backupManager,
            driveClient = drive,
            folderResolver = DriveFolderResolver(linkStore, drive, "ClearTravel"),
            backupsDir = backupsDir,
            downloadDir = File(tmp.root, "cache"),
            driveFolderName = "ClearTravel",
        )

    private fun stageLocalBackup(name: String): File {
        val file = File(backupsDir, name).apply { writeText("zip-$name") }
        backupManager.latest = LocalBackupInfo(fileName = name, createdAt = Instant.EPOCH, sizeBytes = file.length())
        return file
    }

    @Test
    fun `skips when backup-to-drive is disabled, unlinked, or nothing exported`() =
        runTest {
            stageLocalBackup("cleartravel-backup-20260101-0101.zip")
            linkStore.setDriveBackupEnabled(false)
            assertThat(service().uploadLatestBackup()).isEqualTo(DriveBackupUploadResult.Skipped)

            linkStore.setDriveBackupEnabled(true)
            backupManager.latest = null
            assertThat(service().uploadLatestBackup()).isEqualTo(DriveBackupUploadResult.Skipped)

            linkStore.clear()
            assertThat(service().uploadLatestBackup()).isEqualTo(DriveBackupUploadResult.Skipped)
        }

    @Test
    fun `uploads the newest app-storage backup into the drive folder`() =
        runTest {
            stageLocalBackup("cleartravel-backup-20260101-0101.zip")

            val result = service().uploadLatestBackup()

            assertThat(result).isEqualTo(DriveBackupUploadResult.Uploaded)
            assertThat(drive.files.map { it.name }).containsExactly("cleartravel-backup-20260101-0101.zip")
        }

    @Test
    fun `re-uploading the same backup name is deduped`() =
        runTest {
            stageLocalBackup("cleartravel-backup-20260101-0101.zip")
            service().uploadLatestBackup()
            service().uploadLatestBackup()

            assertThat(drive.files).hasSize(1)
        }

    @Test
    fun `prunes drive to the five newest backups`() =
        runTest {
            val svc = service()
            for (i in 1..7) {
                stageLocalBackup("cleartravel-backup-2026010$i-0101.zip")
                assertThat(svc.uploadLatestBackup()).isEqualTo(DriveBackupUploadResult.Uploaded)
            }

            val names = drive.files.map { it.name }.sorted()
            assertThat(names).hasSize(5)
            // The two oldest (day 1 and 2) were pruned.
            assertThat(names.first()).isEqualTo("cleartravel-backup-20260103-0101.zip")
        }

    @Test
    fun `listBackups returns newest first and never creates the folder`() =
        runTest {
            assertThat(service().listBackups()).isEmpty()
            assertThat(drive.folders).isEmpty()

            stageLocalBackup("cleartravel-backup-20260101-0101.zip")
            service().uploadLatestBackup()
            stageLocalBackup("cleartravel-backup-20260102-0101.zip")
            service().uploadLatestBackup()

            val backups = service().listBackups()
            assertThat(backups.map { it.fileName })
                .containsExactly(
                    "cleartravel-backup-20260102-0101.zip",
                    "cleartravel-backup-20260101-0101.zip",
                ).inOrder()
        }

    @Test
    fun `downloadBackup lands the bytes in the cache dir`() =
        runTest {
            stageLocalBackup("cleartravel-backup-20260101-0101.zip")
            service().uploadLatestBackup()
            val backup = service().listBackups().single()

            val file = service().downloadBackup(backup)

            assertThat(file.readText()).isEqualTo("zip-cleartravel-backup-20260101-0101.zip")
        }
}

class FreshInstallDetectorTest {
    private val trips = FakeTripRepository()
    private val trains = FakeTrainRepository()
    private val flights = FakeFlightRepository()
    private val checklists = FakeChecklistRepository()

    private fun detector() = RepositoryFreshInstallDetector(trips, trains, flights, checklists)

    @Test
    fun `zero trips, journeys and checklists is fresh`() =
        runTest {
            assertThat(detector().isFreshInstall()).isTrue()
        }

    @Test
    fun `any existing data makes the install not fresh`() =
        runTest {
            trips.save(Trip(name = "Paris"))
            assertThat(detector().isFreshInstall()).isFalse()

            trips.delete(
                trips.trips.value
                    .single()
                    .id,
            )
            flights.save(FlightJourney(airlineIata = "6E", flightNumber = "1", archived = true))
            assertThat(detector().isFreshInstall()).isFalse()

            flights.delete(
                flights.flights.value
                    .single()
                    .id,
            )
            checklists.save(Checklist(name = "Packing"))
            assertThat(detector().isFreshInstall()).isFalse()
        }
}
