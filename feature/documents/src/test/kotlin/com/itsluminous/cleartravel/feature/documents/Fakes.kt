package com.itsluminous.cleartravel.feature.documents

import com.itsluminous.cleartravel.core.data.repository.TravelDocumentRepository
import com.itsluminous.cleartravel.core.model.TravelDocument
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.time.Instant

/** In-memory [TravelDocumentRepository] mirroring the Room impl's ordering + tombstones. */
class FakeTravelDocumentRepository(
    private val now: () -> Instant = { Instant.parse("2026-09-22T09:00:00Z") },
) : TravelDocumentRepository {
    private val rows = MutableStateFlow<Map<String, TravelDocument>>(emptyMap())

    private fun live(): List<TravelDocument> =
        rows.value.values
            .filter { it.deletedAt == null }
            .sortedWith(compareByDescending<TravelDocument> { it.addedAt }.thenBy { it.name })

    /** Every row including tombstones — for asserting soft deletes. */
    fun all(): List<TravelDocument> = rows.value.values.toList()

    fun seed(vararg documents: TravelDocument) {
        rows.value = rows.value + documents.associateBy { it.id }
    }

    override fun observeAll(): Flow<List<TravelDocument>> = rows.map { live() }

    override fun observeDocument(id: String): Flow<TravelDocument?> = rows.map { it[id]?.takeIf { row -> row.deletedAt == null } }

    override suspend fun getDocument(id: String): TravelDocument? = rows.value[id]?.takeIf { it.deletedAt == null }

    override suspend fun getPendingDriveUploads(): List<TravelDocument> = live().filter { it.driveFileId == null }

    override suspend fun save(document: TravelDocument): TravelDocument {
        val stamped = document.copy(updatedAt = now())
        rows.value = rows.value + (stamped.id to stamped)
        return stamped
    }

    override suspend fun delete(id: String) {
        val current = rows.value[id] ?: return
        rows.value = rows.value + (id to current.copy(deletedAt = now(), updatedAt = now()))
    }
}

/** In-memory [DocumentFileStore]: records copies/deletes, can be told to fail. */
class FakeDocumentFileStore : DocumentFileStore {
    var failNextStore: Boolean = false
    val stored = mutableListOf<Pair<String, String>>()
    val deleted = mutableListOf<String>()

    override suspend fun store(
        uriString: String,
        documentId: String,
    ): StoredDocumentFile? {
        if (failNextStore) {
            failNextStore = false
            return null
        }
        stored += uriString to documentId
        val extension = if (uriString.endsWith(".pdf")) "pdf" else "jpg"
        return StoredDocumentFile(
            path = "/data/documents/$documentId.$extension",
            mimeType =
                if (extension ==
                    "pdf"
                ) {
                    "application/pdf"
                } else {
                    "image/jpeg"
                },
        )
    }

    override suspend fun delete(path: String) {
        deleted += path
    }
}
