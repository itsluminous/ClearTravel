package com.itsluminous.cleartravel.core.data.repository.offline

import com.itsluminous.cleartravel.core.data.repository.TravelDocumentRepository
import com.itsluminous.cleartravel.core.database.dao.TravelDocumentDao
import com.itsluminous.cleartravel.core.database.entity.toEntity
import com.itsluminous.cleartravel.core.database.entity.toModel
import com.itsluminous.cleartravel.core.model.TravelDocument
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/** Room-backed [TravelDocumentRepository] (ADR-027). */
@Singleton
class OfflineTravelDocumentRepository
    @Inject
    constructor(
        private val documentDao: TravelDocumentDao,
        private val clock: Clock,
    ) : TravelDocumentRepository {
        override fun observeAll(): Flow<List<TravelDocument>> = documentDao.observeAll().map { rows -> rows.map { it.toModel() } }

        override fun observeDocument(id: String): Flow<TravelDocument?> = documentDao.observeById(id).map { it?.toModel() }

        override suspend fun getDocument(id: String): TravelDocument? = documentDao.getById(id)?.toModel()

        override suspend fun getPendingDriveUploads(): List<TravelDocument> = documentDao.getPendingDriveUploads().map { it.toModel() }

        override suspend fun save(document: TravelDocument): TravelDocument {
            val stamped = document.copy(updatedAt = clock.instant())
            documentDao.upsert(stamped.toEntity())
            return stamped
        }

        override suspend fun delete(id: String) {
            documentDao.softDelete(id, clock.instant())
        }
    }
