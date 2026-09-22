package com.itsluminous.cleartravel.core.data.repository.offline

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.database.ClearTravelDatabase
import com.itsluminous.cleartravel.core.model.TravelDocumentType
import com.itsluminous.cleartravel.core.testing.Fixtures
import com.itsluminous.cleartravel.core.testing.inMemoryDatabase
import com.itsluminous.cleartravel.core.testing.syncColumns
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OfflineTravelDocumentRepositoryTest {
    private lateinit var db: ClearTravelDatabase
    private lateinit var documents: OfflineTravelDocumentRepository
    private val clock: Clock = Clock.fixed(Fixtures.NOW.plusSeconds(3600), ZoneOffset.UTC)

    @Before
    fun setUp() {
        db = inMemoryDatabase(ApplicationProvider.getApplicationContext())
        documents = OfflineTravelDocumentRepository(db.travelDocumentDao(), clock)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `save bumps updatedAt and round-trips every field`() =
        runTest {
            val document =
                Fixtures.travelDocument(
                    type = TravelDocumentType.VISA,
                    expiryDate = Fixtures.TODAY.plusYears(1),
                    note = "Type C",
                    mimeType = "application/pdf",
                    updatedAt = Fixtures.NOW,
                )

            val saved = documents.save(document)

            assertThat(saved.updatedAt).isEqualTo(clock.instant())
            assertThat(saved).isEqualTo(document.copy(updatedAt = clock.instant()))
            assertThat(documents.getDocument(saved.id)).isEqualTo(saved)
            assertThat(documents.observeDocument(saved.id).first()).isEqualTo(saved)
        }

    @Test
    fun `observeAll lists live documents newest first and hides tombstones`() =
        runTest {
            val older = documents.save(Fixtures.travelDocument(name = "Older", addedAt = Fixtures.NOW.minusSeconds(60)))
            val newer = documents.save(Fixtures.travelDocument(name = "Newer", addedAt = Fixtures.NOW))
            val deleted = documents.save(Fixtures.travelDocument(name = "Gone", addedAt = Fixtures.NOW.plusSeconds(60)))
            documents.delete(deleted.id)

            assertThat(documents.observeAll().first().map { it.name }).containsExactly("Newer", "Older").inOrder()
            assertThat(documents.getDocument(deleted.id)).isNull()
            assertThat(documents.observeDocument(deleted.id).first()).isNull()
            assertThat(older.id).isNotEqualTo(newer.id)
        }

    @Test
    fun `delete is a soft delete that bumps updatedAt (ADR-002)`() =
        runTest {
            val saved = documents.save(Fixtures.travelDocument())
            val laterClock = Clock.fixed(clock.instant().plusSeconds(30), ZoneOffset.UTC)
            OfflineTravelDocumentRepository(db.travelDocumentDao(), laterClock).delete(saved.id)

            val columns = db.syncColumns("travel_documents", saved.id)
            assertThat(columns).isNotNull()
            assertThat(columns!!.deletedAtEpochMillis).isEqualTo(laterClock.instant().toEpochMilli())
            assertThat(columns.updatedAtEpochMillis).isEqualTo(laterClock.instant().toEpochMilli())
            assertThat(db.backupDao().dumpTravelDocuments()).hasSize(1)
        }

    @Test
    fun `save of an existing id updates in place (rename)`() =
        runTest {
            val saved = documents.save(Fixtures.travelDocument(name = "Passport"))
            documents.save(saved.copy(name = "Passport – Ada", type = TravelDocumentType.OTHER))

            val all = documents.observeAll().first()
            assertThat(all).hasSize(1)
            assertThat(all.single().name).isEqualTo("Passport – Ada")
            assertThat(all.single().type).isEqualTo(TravelDocumentType.OTHER)
        }

    @Test
    fun `pending drive uploads are the live local-only rows`() =
        runTest {
            val local = documents.save(Fixtures.travelDocument(name = "Local"))
            documents.save(Fixtures.travelDocument(name = "Uploaded", driveFileId = "drive-1"))
            val deleted = documents.save(Fixtures.travelDocument(name = "Deleted"))
            documents.delete(deleted.id)

            assertThat(documents.getPendingDriveUploads().map { it.id }).containsExactly(local.id)
        }
}
