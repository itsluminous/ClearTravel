package com.itsluminous.cleartravel.feature.itinerary.logic

import com.itsluminous.cleartravel.core.designsystem.component.moved
import com.itsluminous.cleartravel.core.model.ItineraryItem
import com.itsluminous.cleartravel.core.model.Trip
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/**
 * One day bucket of a trip's itinerary: items ordered by [ItineraryItem.orderInDay] —
 * the single source of truth for display order (ADR-029). Time-based auto-sort and
 * manual drags both WRITE `orderInDay`; grouping never re-sorts by time on read.
 */
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
 * Moves the item at [from] to [to] within [dayIndex] (positions in the day's
 * `orderInDay`-sorted list, as the drag helper reports them) and returns ONLY the
 * rows whose `orderInDay` changed (ready for `saveAll`). ADR-029: a manual drag
 * rewrites the day's explicit order, which stays the source of truth until an item's
 * time is next set or changed. Empty when the indices are out of range or equal.
 */
fun reorderWithinDay(
    items: List<ItineraryItem>,
    dayIndex: Int,
    from: Int,
    to: Int,
): List<ItineraryItem> {
    val dayItems = sortedDay(items, dayIndex)
    if (from == to || from !in dayItems.indices || to !in dayItems.indices) return emptyList()
    return renumber(dayItems.moved(from, to))
}

/**
 * Re-derives [dayIndex]'s `orderInDay` from planned times (ADR-029 auto-sort): timed
 * items ascending, untimed items last; ties and untimed items keep their current
 * relative order (stable). Returns ONLY the rows whose `orderInDay` changed.
 * Callers apply it whenever an item's time is set or changed — never on read.
 */
fun sortDayByTime(
    items: List<ItineraryItem>,
    dayIndex: Int,
): List<ItineraryItem> {
    val dayItems = sortedDay(items, dayIndex)
    val sorted = dayItems.sortedWith(compareBy(nullsLast()) { parsePlannedTime(it.plannedTime) })
    return renumber(sorted)
}

/**
 * The "HH:mm" (or "H:mm") planned time as a [LocalTime]; null for blank or unparseable
 * text, so free-form notes in the field simply sort as "unscheduled".
 */
fun parsePlannedTime(text: String): LocalTime? {
    val match = TIME_PATTERN.matchEntire(text.trim()) ?: return null
    val hour = match.groupValues[1].toInt()
    val minute = match.groupValues[2].toInt()
    if (hour > 23 || minute > 59) return null
    return LocalTime.of(hour, minute)
}

private val TIME_PATTERN = Regex("""(\d{1,2}):(\d{2})""")

private fun sortedDay(
    items: List<ItineraryItem>,
    dayIndex: Int,
): List<ItineraryItem> =
    items
        .filter { it.dayIndex == dayIndex }
        .sortedWith(compareBy({ it.orderInDay }, { it.name }))

/** Assigns 0..n-1 positions and keeps only the rows that actually moved. */
private fun renumber(ordered: List<ItineraryItem>): List<ItineraryItem> =
    ordered.mapIndexedNotNull { position, row ->
        if (row.orderInDay == position) null else row.copy(orderInDay = position)
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
