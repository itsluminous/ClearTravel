package com.itsluminous.cleartravel.core.model

/** Which aggregate an [Attachment] belongs to (polymorphic owner reference). */
enum class AttachmentOwnerType(
    val storageValue: String,
) {
    TRAIN("train"),
    FLIGHT("flight"),
    ITINERARY("itinerary"),
    ;

    companion object {
        /** Parses a stored value; unknown/absent values fall back to [ITINERARY]. */
        fun fromStorage(value: String?): AttachmentOwnerType = entries.firstOrNull { it.storageValue == value } ?: ITINERARY
    }
}
