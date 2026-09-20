package com.itsluminous.cleartravel.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.itsluminous.cleartravel.core.database.entity.AttachmentEntity
import com.itsluminous.cleartravel.core.model.AttachmentOwnerType
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/** File attachments (polymorphic owner). Read queries exclude tombstones (ADR-002). */
@Dao
interface AttachmentDao {
    @Query("SELECT * FROM attachments WHERE owner_type = :ownerType AND owner_id = :ownerId AND deleted_at IS NULL ORDER BY updated_at")
    fun observeForOwner(
        ownerType: AttachmentOwnerType,
        ownerId: String,
    ): Flow<List<AttachmentEntity>>

    @Query("SELECT * FROM attachments WHERE id = :id AND deleted_at IS NULL")
    suspend fun getById(id: String): AttachmentEntity?

    /** All live attachments not yet uploaded to Drive (upload queue candidates). */
    @Query("SELECT * FROM attachments WHERE drive_file_id IS NULL AND deleted_at IS NULL ORDER BY updated_at")
    suspend fun getPendingDriveUploads(): List<AttachmentEntity>

    @Upsert
    suspend fun upsert(attachment: AttachmentEntity)

    /** Soft delete (ADR-002): sets the tombstone and bumps `updated_at` in one write. */
    @Query("UPDATE attachments SET deleted_at = :at, updated_at = :at WHERE id = :id")
    suspend fun softDelete(
        id: String,
        at: Instant,
    )

    /** Soft-deletes every live attachment of an owner (used on owner delete). */
    @Query(
        "UPDATE attachments SET deleted_at = :at, updated_at = :at WHERE owner_type = :ownerType AND owner_id = :ownerId AND deleted_at IS NULL",
    )
    suspend fun softDeleteForOwner(
        ownerType: AttachmentOwnerType,
        ownerId: String,
        at: Instant,
    )
}
