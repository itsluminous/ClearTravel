package com.itsluminous.cleartravel.core.model

/** Kind of itinerary item within a trip day: a place to visit or a commute leg. */
enum class ItineraryItemType(
    val storageValue: String,
) {
    PLACE("place"),
    COMMUTE("commute"),
    ;

    companion object {
        /** Parses a stored value; unknown/absent values fall back to [PLACE]. */
        fun fromStorage(value: String?): ItineraryItemType = entries.firstOrNull { it.storageValue == value } ?: PLACE
    }
}

/** Category of a PLACE itinerary item (drives marker color/icon and filtering). */
enum class PlaceCategory(
    val storageValue: String,
) {
    SIGHT("sight"),
    FOOD("food"),
    STAY("stay"),
    SHOPPING("shopping"),
    ACTIVITY("activity"),
    OTHER("other"),
    ;

    companion object {
        /** Parses a stored value; unknown/absent values fall back to [OTHER]. */
        fun fromStorage(value: String?): PlaceCategory = entries.firstOrNull { it.storageValue == value } ?: OTHER
    }
}

/** Transport mode of a COMMUTE itinerary item. */
enum class CommuteMode(
    val storageValue: String,
) {
    TRAIN("train"),
    FLIGHT("flight"),
    CAB("cab"),
    BUS("bus"),
    WALK("walk"),
    FERRY("ferry"),
    OTHER("other"),
    ;

    companion object {
        /** Parses a stored value; unknown/absent values fall back to [OTHER]. */
        fun fromStorage(value: String?): CommuteMode = entries.firstOrNull { it.storageValue == value } ?: OTHER
    }
}
