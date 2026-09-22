package com.itsluminous.cleartravel.feature.itinerary.logic

import com.itsluminous.cleartravel.core.model.ItineraryItem
import com.itsluminous.cleartravel.core.model.Trip
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** One day bucket of a trip's itinerary: items ordered by [ItineraryItem.orderInDay]. */
data class ItineraryDay(
    /** 0-based day within the trip ("Day 1" renders dayIndex 0). */
    val dayIndex: Int,
    /** Calendar date of the day when known (from items or trip start date). */
    val date: LocalDate?,
    val items: List<ItineraryItem>,
)

/**
 * Buckets [items] into ordered day groups: days sorted ascending by `dayIndex`,
 * items within a day sorted by `orderInDay` (name as a stable tiebreak). Days with
 * no items produce no bucket — the UI derives empty-day slots from the trip length.
 */
fun groupItemsByDay(items: List<ItineraryItem>): List<ItineraryDay> =
    items
        .groupBy { it.dayIndex }
        .entries
        .sortedBy { it.key }
        .map { (dayIndex, dayItems) ->
            val sorted = dayItems.sortedWith(compareBy({ it.orderInDay }, { it.name }))
            ItineraryDay(
                dayIndex = dayIndex,
                date = sorted.firstNotNullOfOrNull { it.date },
                items = sorted,
            )
        }

/** Next free `orderInDay` for a new item appended to [dayIndex]. */
fun nextOrderInDay(
    items: List<ItineraryItem>,
    dayIndex: Int,
): Int = (items.filter { it.dayIndex == dayIndex }.maxOfOrNull { it.orderInDay } ?: -1) + 1

/**
 * Moves [itemId] one step within its day ([delta] -1 = up, +1 = down) and returns
 * ONLY the rows whose `orderInDay` changed (ready for `saveAll`). Returns empty when
 * the item is absent or already at the edge — nothing to persist.
 */
fun moveWithinDay(
    items: List<ItineraryItem>,
    itemId: String,
    delta: Int,
): List<ItineraryItem> {
    val item = items.firstOrNull { it.id == itemId } ?: return emptyList()
    val dayItems =
        items
            .filter { it.dayIndex == item.dayIndex }
            .sortedWith(compareBy({ it.orderInDay }, { it.name }))
    val index = dayItems.indexOfFirst { it.id == itemId }
    val targetIndex = index + delta
    if (targetIndex !in dayItems.indices) return emptyList()
    val reordered = dayItems.toMutableList()
    reordered.add(targetIndex, reordered.removeAt(index))
    return reordered.mapIndexedNotNull { position, row ->
        if (row.orderInDay == position) null else row.copy(orderInDay = position)
    }
}

/**
 * Number of day slots the item form offers: the trip's dated length when both dates
 * are set, at least one slot past the last populated day, and never fewer than 1.
 */
fun dayCount(
    trip: Trip?,
    items: List<ItineraryItem>,
): Int {
    val fromDates =
        if (trip?.startDate != null && trip.endDate != null && !trip.endDate!!.isBefore(trip.startDate)) {
            (ChronoUnit.DAYS.between(trip.startDate, trip.endDate) + 1).toInt()
        } else {
            0
        }
    val fromItems = (items.maxOfOrNull { it.dayIndex } ?: -1) + 2
    return maxOf(fromDates, fromItems, 1)
}

/** Calendar date of [dayIndex] when the trip has a start date; null otherwise. */
fun dateForDay(
    trip: Trip?,
    dayIndex: Int,
): LocalDate? = trip?.startDate?.plusDays(dayIndex.toLong())

/**
 * The 0-based day slot of [date] within the trip — when the trip has a start date and
 * the day lies within the first [dayCount] slots — else null. Used to drop a linked
 * journey onto its own day (ADR-028) without ever creating slots the form does not offer.
 */
fun dayIndexFor(
    trip: Trip?,
    date: LocalDate?,
    dayCount: Int,
): Int? {
    val start = trip?.startDate ?: return null
    date ?: return null
    val index = ChronoUnit.DAYS.between(start, date)
    return index.toInt().takeIf { index >= 0 && index < dayCount }
}
