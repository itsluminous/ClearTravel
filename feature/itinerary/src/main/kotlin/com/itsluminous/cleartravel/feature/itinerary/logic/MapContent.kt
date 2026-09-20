package com.itsluminous.cleartravel.feature.itinerary.logic

import com.itsluminous.cleartravel.core.model.ItineraryItem
import com.itsluminous.cleartravel.core.model.ItineraryItemType

/** A plain coordinate pair — framework-free stand-in for Maps `LatLng`. */
data class MapPoint(
    val latitude: Double,
    val longitude: Double,
)

/** One numbered place marker: [numberInDay] is 1-based within the item's day. */
data class TripMapMarker(
    val item: ItineraryItem,
    val point: MapPoint,
    val dayIndex: Int,
    val numberInDay: Int,
)

/** Ordered polyline of one day's located items. */
data class TripDayPath(
    val dayIndex: Int,
    val points: List<MapPoint>,
)

/** Everything the map view renders, derived purely from the item list. */
data class TripMapContent(
    val markers: List<TripMapMarker>,
    val paths: List<TripDayPath>,
) {
    val isEmpty: Boolean get() = markers.isEmpty() && paths.isEmpty()
}

/**
 * Builds map content from a trip's items: PLACE items with coordinates become
 * numbered markers (1-based per day, in day order); each day's located items (any
 * type) connect into a polyline in `orderInDay` order. Items without coordinates
 * are skipped everywhere; a day contributes a path only with 2+ located points.
 */
fun buildTripMapContent(items: List<ItineraryItem>): TripMapContent {
    val markers = mutableListOf<TripMapMarker>()
    val paths = mutableListOf<TripDayPath>()
    groupItemsByDay(items).forEach { day ->
        val located = day.items.filter { it.latitude != null && it.longitude != null }
        var placeNumber = 0
        located.forEach { item ->
            if (item.type == ItineraryItemType.PLACE) {
                placeNumber += 1
                markers +=
                    TripMapMarker(
                        item = item,
                        point = MapPoint(item.latitude!!, item.longitude!!),
                        dayIndex = day.dayIndex,
                        numberInDay = placeNumber,
                    )
            }
        }
        if (located.size >= 2) {
            paths +=
                TripDayPath(
                    dayIndex = day.dayIndex,
                    points = located.map { MapPoint(it.latitude!!, it.longitude!!) },
                )
        }
    }
    return TripMapContent(markers = markers, paths = paths)
}
