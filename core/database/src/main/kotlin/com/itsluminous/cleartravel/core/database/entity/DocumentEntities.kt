package com.itsluminous.cleartravel.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.itsluminous.cleartravel.core.model.TravelDocument
import com.itsluminous.cleartravel.core.model.TravelDocumentType
import java.time.Instant
import java.time.LocalDate

/** Room row for [TravelDocument] (ADR-027, schema v3). */
@Entity(tableName = "travel_documents")
data class TravelDocumentEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: TravelDocumentType,
    @ColumnInfo(name = "file_path") val filePath: String,
    @ColumnInfo(name = "mime_type") val mimeType: String,
    @ColumnInfo(name = "added_at") val addedAt: Instant,
    @ColumnInfo(name = "expiry_date") val expiryDate: LocalDate?,
    val note: String,
    @ColumnInfo(name = "drive_file_id") val driveFileId: String?,
    @ColumnInfo(name = "updated_at") val updatedAt: Instant,
    @ColumnInfo(name = "deleted_at") val deletedAt: Instant?,
)

fun TravelDocument.toEntity(): TravelDocumentEntity =
    TravelDocumentEntity(
        id = id,
        name = name,
        type = type,
        filePath = filePath,
        mimeType = mimeType,
        addedAt = addedAt,
        expiryDate = expiryDate,
        note = note,
        driveFileId = driveFileId,
        updatedAt = updatedAt,
        deletedAt = deletedAt,
    )

fun TravelDocumentEntity.toModel(): TravelDocument =
    TravelDocument(
        id = id,
        name = name,
        type = type,
        filePath = filePath,
        mimeType = mimeType,
        addedAt = addedAt,
        expiryDate = expiryDate,
        note = note,
        driveFileId = driveFileId,
        updatedAt = updatedAt,
        deletedAt = deletedAt,
    )
