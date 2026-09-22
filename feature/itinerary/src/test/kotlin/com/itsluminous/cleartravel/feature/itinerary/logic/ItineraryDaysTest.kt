package com.itsluminous.cleartravel.feature.itinerary.logic

import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.testing.Fixtures
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class ItineraryDaysTest {
    @Test
    fun `groupItemsByDay buckets days in ascending order`() {
        val items =
            listOf(
                Fixtures.itineraryItem(dayIndex = 2, name = "Later"),
                Fixtures.itineraryItem(dayIndex = 0, name = "First"),
                Fixtures.itineraryItem(dayIndex = 1, name = "Middle"),
            )

        val days = groupItemsByDay(items)

        assertThat(days.map { it.dayIndex }).containsExactly(0, 1, 2).inOrder()
        assertThat(days.flatMap { it.items }.map { it.name })
            .containsExactly("First", "Middle", "Later")
            .inOrder()
    }

    @Test
    fun `groupItemsByDay orders items within a day by orderInDay`() {
        val items =
            listOf(
                Fixtures.itineraryItem(dayIndex = 0, orderInDay = 2, name = "Third"),
                Fixtures.itineraryItem(dayIndex = 0, orderInDay = 0, name = "First"),
                Fixtures.itineraryItem(dayIndex = 0, orderInDay = 1, name = "Second"),
            )

        val days = groupItemsByDay(items)

        assertThat(days).hasSize(1)
        assertThat(days.single().items.map { it.name })
            .containsExactly("First", "Second", "Third")
            .inOrder()
    }

    @Test
    fun `groupItemsByDay picks the day date from its items`() {
        val date = LocalDate.parse("2026-10-02")
        val items =
            listOf(
                Fixtures.itineraryItem(dayIndex = 0, orderInDay = 0, date = null),
                Fixtures.itineraryItem(dayIndex = 0, orderInDay = 1, date = date),
            )

        assertThat(groupItemsByDay(items).single().date).isEqualTo(date)
    }

    @Test
    fun `groupItemsByDay of no items is empty`() {
        assertThat(groupItemsByDay(emptyList())).isEmpty()
    }

    @Test
    fun `nextOrderInDay appends after the day's max order`() {
        val items =
            listOf(
                Fixtures.itineraryItem(dayIndex = 0, orderInDay = 3),
                Fixtures.itineraryItem(dayIndex = 1, orderInDay = 9),
            )

        assertThat(nextOrderInDay(items, 0)).isEqualTo(4)
        assertThat(nextOrderInDay(items, 2)).isEqualTo(0)
    }

    // ADR-029: a drag drop rewrites the day's explicit order — only the rows that moved.
    @Test
    fun `reorderWithinDay moves the dragged row and renumbers only the affected rows`() {
        val a = Fixtures.itineraryItem(id = "a", dayIndex = 0, orderInDay = 0, name = "A")
        val b = Fixtures.itineraryItem(id = "b", dayIndex = 0, orderInDay = 1, name = "B")
        val c = Fixtures.itineraryItem(id = "c", dayIndex = 0, orderInDay = 2, name = "C")
        val otherDay = Fixtures.itineraryItem(id = "d", dayIndex = 1, orderInDay = 0, name = "D")

        val updates = reorderWithinDay(listOf(a, b, c, otherDay), dayIndex = 0, from = 2, to = 0)

        assertThat(updates.map { it.id to it.orderInDay })
            .containsExactly("c" to 0, "a" to 1, "b" to 2)
    }

    @Test
    fun `reorderWithinDay ignores same-position and out-of-range drops`() {
        val a = Fixtures.itineraryItem(id = "a", dayIndex = 0, orderInDay = 0)
        val b = Fixtures.itineraryItem(id = "b", dayIndex = 0, orderInDay = 1)

        assertThat(reorderWithinDay(listOf(a, b), dayIndex = 0, from = 1, to = 1)).isEmpty()
        assertThat(reorderWithinDay(listOf(a, b), dayIndex = 0, from = 0, to = 5)).isEmpty()
        assertThat(reorderWithinDay(listOf(a, b), dayIndex = 3, from = 0, to = 1)).isEmpty()
    }

    // ADR-029 auto-sort: timed items ascending, untimed last, stable otherwise.
    @Test
    fun `sortDayByTime orders timed items by time and puts untimed items last`() {
        val late = Fixtures.itineraryItem(id = "late", dayIndex = 0, orderInDay = 0, plannedTime = "18:30")
        val untimed = Fixtures.itineraryItem(id = "none", dayIndex = 0, orderInDay = 1, plannedTime = "")
        val early = Fixtures.itineraryItem(id = "early", dayIndex = 0, orderInDay = 2, plannedTime = "09:15")
        val otherDay = Fixtures.itineraryItem(id = "other", dayIndex = 1, orderInDay = 0, plannedTime = "01:00")

        val updates = sortDayByTime(listOf(late, untimed, early, otherDay), dayIndex = 0)

        assertThat(updates.map { it.id to it.orderInDay })
            .containsExactly("early" to 0, "late" to 1, "none" to 2)
    }

    @Test
    fun `sortDayByTime is stable for ties and untimed items and reports nothing when already sorted`() {
        val a = Fixtures.itineraryItem(id = "a", dayIndex = 0, orderInDay = 0, plannedTime = "10:00")
        val b = Fixtures.itineraryItem(id = "b", dayIndex = 0, orderInDay = 1, plannedTime = "10:00")
        val c = Fixtures.itineraryItem(id = "c", dayIndex = 0, orderInDay = 2, plannedTime = "")
        val d = Fixtures.itineraryItem(id = "d", dayIndex = 0, orderInDay = 3, plannedTime = "notes")

        assertThat(sortDayByTime(listOf(d, c, b, a), dayIndex = 0)).isEmpty()
    }

    @Test
    fun `sortDayByTime treats an unparseable time as unscheduled`() {
        val garbage = Fixtures.itineraryItem(id = "g", dayIndex = 0, orderInDay = 0, plannedTime = "evening")
        val timed = Fixtures.itineraryItem(id = "t", dayIndex = 0, orderInDay = 1, plannedTime = "7:05")

        assertThat(sortDayByTime(listOf(garbage, timed), dayIndex = 0).map { it.id to it.orderInDay })
            .containsExactly("t" to 0, "g" to 1)
    }

    @Test
    fun `parsePlannedTime accepts HH-mm and H-mm and rejects everything else`() {
        assertThat(parsePlannedTime("09:05")).isEqualTo(LocalTime.of(9, 5))
        assertThat(parsePlannedTime(" 7:30 ")).isEqualTo(LocalTime.of(7, 30))
        assertThat(parsePlannedTime("23:59")).isEqualTo(LocalTime.of(23, 59))
        assertThat(parsePlannedTime("24:00")).isNull()
        assertThat(parsePlannedTime("10:60")).isNull()
        assertThat(parsePlannedTime("")).isNull()
        assertThat(parsePlannedTime("around 10")).isNull()
    }

    @Test
    fun `dayCount covers the dated trip length and one slot past the last used day`() {
        val datedTrip =
            Fixtures.trip(
                startDate = LocalDate.parse("2026-09-20"),
                endDate = LocalDate.parse("2026-09-24"),
            )

        assertThat(dayCount(datedTrip, emptyList())).isEqualTo(5)
        assertThat(dayCount(null, emptyList())).isEqualTo(1)
        // An item on day 6 of a 5-day trip still gets an extra slot offered.
        assertThat(dayCount(datedTrip, listOf(Fixtures.itineraryItem(dayIndex = 6)))).isEqualTo(8)
    }

    @Test
    fun `dateForDay offsets the trip start date`() {
        val trip = Fixtures.trip(startDate = LocalDate.parse("2026-09-20"))

        assertThat(dateForDay(trip, 2)).isEqualTo(LocalDate.parse("2026-09-22"))
        assertThat(dateForDay(Fixtures.trip(startDate = null), 2)).isNull()
        assertThat(dateForDay(null, 2)).isNull()
    }

    /** ADR-028: a linked journey lands on its own day only when the form offers that slot. */
    @Test
    fun `dayIndexFor maps a date inside the offered slots and rejects everything else`() {
        val trip = Fixtures.trip(startDate = LocalDate.parse("2026-09-20"))

        assertThat(dayIndexFor(trip, LocalDate.parse("2026-09-20"), dayCount = 3)).isEqualTo(0)
        assertThat(dayIndexFor(trip, LocalDate.parse("2026-09-22"), dayCount = 3)).isEqualTo(2)
        assertThat(dayIndexFor(trip, LocalDate.parse("2026-09-23"), dayCount = 3)).isNull()
        assertThat(dayIndexFor(trip, LocalDate.parse("2026-09-19"), dayCount = 3)).isNull()
        assertThat(dayIndexFor(trip, null, dayCount = 3)).isNull()
        assertThat(dayIndexFor(Fixtures.trip(startDate = null), LocalDate.parse("2026-09-20"), dayCount = 3)).isNull()
        assertThat(dayIndexFor(null, LocalDate.parse("2026-09-20"), dayCount = 3)).isNull()
    }
}
