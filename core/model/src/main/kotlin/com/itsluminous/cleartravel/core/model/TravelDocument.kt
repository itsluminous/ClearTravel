package com.itsluminous.cleartravel.core.model

import java.time.Instant
import java.time.LocalDate

/**
 * Kind of a [TravelDocument] (ADR-027). Stored by [storageValue], never by `name()`.
 * The type drives the list icon and the default display name; [OTHER] carries a
 * free-text name only.
 */
enum class TravelDocumentType(
    val storageValue: String,
) {
    PASSPORT("passport"),
    VISA("visa"),
    ID_CARD("id_card"),
    DRIVING_LICENSE("driving_license"),
    INSURANCE("insurance"),
    VACCINATION("vaccination"),
    TICKET("ticket"),
    OTHER("other"),
    ;

    companion object {
        /** Parses a stored value; unknown/absent values fall back to [OTHER]. */
        fun fromStorage(value: String?): TravelDocumentType = entries.firstOrNull { it.storageValue == value } ?: OTHER
    }
}

/**
 * A first-class travel document (passport scan, visa, insurance PDF…) stored as a
 * local file in app-private storage (ADR-027). Not owned by any journey or trip —
 * that is why it is NOT an [Attachment]. Local-only for now: [driveFileId] is
 * reserved for the Drive follow-up and stays null.
 */
data class TravelDocument(
    override val id: String = EntityIds.newId(),
    /** User-visible label; defaults to the type's preset name, freely editable. */
    val name: String,
    val type: TravelDocumentType = TravelDocumentType.OTHER,
    /** Absolute path of the stored copy under `filesDir/documents/`. */
    val filePath: String,
    val mimeType: String = "",
    val addedAt: Instant = Instant.now(),
    /** Optional expiry (passports, visas, insurance); null = not applicable/unknown. */
    val expiryDate: LocalDate? = null,
    val note: String = "",
    /** Google Drive file id once uploaded; null = local-only (always null today). */
    val driveFileId: String? = null,
    override val updatedAt: Instant = Instant.now(),
    override val deletedAt: Instant? = null,
) : SyncableEntity
