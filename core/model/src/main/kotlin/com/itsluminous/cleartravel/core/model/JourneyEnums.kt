package com.itsluminous.cleartravel.core.model

/**
 * Which journey aggregate a cross-reference points at (e.g. an itinerary commute leg
 * linked to a train ticket or flight journey already in the app).
 */
enum class JourneyType(
    val storageValue: String,
) {
    TRAIN("train"),
    FLIGHT("flight"),
    ;

    companion object {
        /** Parses a stored value; unknown/absent values fall back to [TRAIN]. */
        fun fromStorage(value: String?): JourneyType = entries.firstOrNull { it.storageValue == value } ?: TRAIN
    }
}

/** Live status of a flight journey, as reported by a [FlightStatus] provider. */
enum class FlightStatus(
    val storageValue: String,
) {
    SCHEDULED("scheduled"),
    BOARDING("boarding"),
    DEPARTED("departed"),
    LANDED("landed"),
    DELAYED("delayed"),
    CANCELLED("cancelled"),
    UNKNOWN("unknown"),
    ;

    companion object {
        /** Parses a stored value; unknown/absent values fall back to [UNKNOWN]. */
        fun fromStorage(value: String?): FlightStatus = entries.firstOrNull { it.storageValue == value } ?: UNKNOWN
    }
}
