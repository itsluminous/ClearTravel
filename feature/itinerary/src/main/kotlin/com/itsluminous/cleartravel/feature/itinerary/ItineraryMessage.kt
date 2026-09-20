package com.itsluminous.cleartravel.feature.itinerary

/**
 * One-shot user-visible messages emitted by the itinerary ViewModels. Kept as an
 * enum (not string resources) so ViewModels stay framework-free and unit-testable;
 * the UI maps each value to a localized `itinerary_*` string.
 */
enum class ItineraryMessage {
    TRIP_NAME_REQUIRED,
    TRIP_DATES_INVALID,
    TRIP_SAVED,
    TRIP_DELETED,
    TRIP_ARCHIVED,
    TRIP_UNARCHIVED,
    ITEM_NAME_REQUIRED,
    ITEM_ROUTE_REQUIRED,
    ITEM_SAVED,
    ITEM_DELETED,
}
