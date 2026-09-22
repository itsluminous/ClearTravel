package com.itsluminous.cleartravel.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.itsluminous.cleartravel.core.database.entity.TravelDocumentEntity
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/** Travel documents (ADR-027). Read queries exclude tombstones (ADR-002). */
@Dao
interface TravelDocumentDao {
    /** Live documents, newest first. */
    @Query("SELECT * FROM travel_documents WHERE deleted_at IS NULL ORDER BY added_at DESC, name")
    fun observeAll(): Flow<List<TravelDocumentEntity>>

    @Query("SELECT * FROM travel_documents WHERE id = :id AND deleted_at IS NULL")
    fun observeById(id: String): Flow<TravelDocumentEntity?>

    @Query("SELECT * FROM travel_documents WHERE id = :id AND deleted_at IS NULL")
    suspend fun getById(id: String): TravelDocumentEntity?

    /** Live documents not yet uploaded to Drive — reserved for the Drive follow-up (ADR-027). */
    @Query("SELECT * FROM travel_documents WHERE drive_file_id IS NULL AND deleted_at IS NULL ORDER BY updated_at")
    suspend fun getPendingDriveUploads(): List<TravelDocumentEntity>

    @Upsert
    suspend fun upsert(document: TravelDocumentEntity)

    /** Soft delete (ADR-002): sets the tombstone and bumps `updated_at` in one write. */
    @Query("UPDATE travel_documents SET deleted_at = :at, updated_at = :at WHERE id = :id")
    suspend fun softDelete(
        id: String,
        at: Instant,
    )
}
