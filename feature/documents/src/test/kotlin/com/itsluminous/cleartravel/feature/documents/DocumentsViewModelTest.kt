package com.itsluminous.cleartravel.feature.documents

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.model.TravelDocumentType
import com.itsluminous.cleartravel.core.testing.Fixtures
import com.itsluminous.cleartravel.core.testing.MainDispatcherRule
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.time.Clock
import java.time.ZoneOffset

class DocumentsViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = FakeTravelDocumentRepository()
    private val fileStore = FakeDocumentFileStore()
    private val clock: Clock = Clock.fixed(Fixtures.NOW, ZoneOffset.UTC)

    private fun viewModel() = DocumentsViewModel(repository, fileStore, clock)

    @Test
    fun `documents emits live rows newest first and hides tombstones`() =
        runTest {
            repository.seed(
                Fixtures.travelDocument(name = "Older", addedAt = Fixtures.NOW.minusSeconds(100)),
                Fixtures.travelDocument(name = "Newer", addedAt = Fixtures.NOW),
                Fixtures.travelDocument(name = "Gone", addedAt = Fixtures.NOW.plusSeconds(5), deletedAt = Fixtures.NOW),
            )

            viewModel().documents.test {
                var value = awaitItem()
                while (value == null) value = awaitItem()
                assertThat(value.map { it.name }).containsExactly("Newer", "Older").inOrder()
            }
        }

    @Test
    fun `addDocument copies the file then saves a row with the typed name and expiry`() =
        runTest {
            val vm = viewModel()
            val expiry = Fixtures.TODAY.plusYears(9)

            vm.events.test {
                vm.addDocument(
                    uriString = "content://picker/passport.pdf",
                    type = TravelDocumentType.PASSPORT,
                    name = "  Ada's passport ",
                    fallbackName = "Passport",
                    expiryDate = expiry,
                    note = " P<IND ",
                )
                val event = awaitItem()
                assertThat(event).isInstanceOf(DocumentsEvent.Added::class.java)
                val saved = repository.getDocument((event as DocumentsEvent.Added).documentId)!!
                assertThat(saved.name).isEqualTo("Ada's passport")
                assertThat(saved.type).isEqualTo(TravelDocumentType.PASSPORT)
                assertThat(saved.expiryDate).isEqualTo(expiry)
                assertThat(saved.note).isEqualTo("P<IND")
                assertThat(saved.filePath).isEqualTo("/data/documents/${saved.id}.pdf")
                assertThat(saved.mimeType).isEqualTo("application/pdf")
                assertThat(saved.addedAt).isEqualTo(Fixtures.NOW)
                assertThat(saved.driveFileId).isNull()
                assertThat(fileStore.stored).containsExactly("content://picker/passport.pdf" to saved.id)
            }
        }

    @Test
    fun `addDocument with a blank name uses the preset label`() =
        runTest {
            val vm = viewModel()
            vm.events.test {
                vm.addDocument("content://picker/visa.jpg", TravelDocumentType.VISA, "   ", "Visa", null)
                awaitItem()
            }
            assertThat(repository.all().single().name).isEqualTo("Visa")
            assertThat(repository.all().single().expiryDate).isNull()
        }

    @Test
    fun `addDocument writes nothing when the file copy fails`() =
        runTest {
            fileStore.failNextStore = true
            val vm = viewModel()

            vm.events.test {
                vm.addDocument("content://picker/broken", TravelDocumentType.OTHER, "x", "Other document", null)
                assertThat(awaitItem()).isEqualTo(DocumentsEvent.AddFailed)
            }
            assertThat(repository.all()).isEmpty()
            assertThat(fileStore.stored).isEmpty()
        }

    @Test
    fun `updateDetails renames, retypes and re-dates without touching the file`() =
        runTest {
            val original = Fixtures.travelDocument(name = "Passport", type = TravelDocumentType.PASSPORT)
            repository.seed(original)
            val vm = viewModel()

            vm.events.test {
                vm.updateDetails(original.id, " Old passport ", TravelDocumentType.OTHER, Fixtures.TODAY, "expired copy")
                assertThat(awaitItem()).isEqualTo(DocumentsEvent.Updated)
            }
            val updated = repository.getDocument(original.id)!!
            assertThat(updated.name).isEqualTo("Old passport")
            assertThat(updated.type).isEqualTo(TravelDocumentType.OTHER)
            assertThat(updated.expiryDate).isEqualTo(Fixtures.TODAY)
            assertThat(updated.note).isEqualTo("expired copy")
            assertThat(updated.filePath).isEqualTo(original.filePath)
            assertThat(fileStore.stored).isEmpty()
            assertThat(fileStore.deleted).isEmpty()
        }

    @Test
    fun `updateDetails ignores a blank name`() =
        runTest {
            val original = Fixtures.travelDocument(name = "Passport")
            repository.seed(original)

            viewModel().updateDetails(original.id, "   ", TravelDocumentType.VISA, null, "")

            assertThat(repository.getDocument(original.id)).isEqualTo(original)
        }

    @Test
    fun `delete soft-deletes the row and removes the stored file`() =
        runTest {
            val document = Fixtures.travelDocument(name = "Insurance", filePath = "/data/documents/abc.pdf")
            repository.seed(document)
            val vm = viewModel()

            vm.events.test {
                vm.delete(document.id)
                assertThat(awaitItem()).isEqualTo(DocumentsEvent.Deleted("Insurance"))
            }
            assertThat(repository.getDocument(document.id)).isNull()
            assertThat(repository.all().single().deletedAt).isNotNull()
            assertThat(fileStore.deleted).containsExactly("/data/documents/abc.pdf")
        }

    @Test
    fun `delete of an unknown id is a no-op`() =
        runTest {
            viewModel().delete("missing")
            assertThat(fileStore.deleted).isEmpty()
            assertThat(repository.all()).isEmpty()
        }
}
