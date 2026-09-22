package com.itsluminous.cleartravel.feature.itinerary.logic

import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.testing.Fixtures
import org.junit.Test
import java.time.LocalDate

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

    @Test
    fun `moveWithinDay swaps neighbours and returns only changed rows`() {
        val first = Fixtures.itineraryItem(id = "a", dayIndex = 0, orderInDay = 0, name = "A")
        val second = Fixtures.itineraryItem(id = "b", dayIndex = 0, orderInDay = 1, name = "B")
        val otherDay = Fixtures.itineraryItem(id = "c", dayIndex = 1, orderInDay = 0, name = "C")

        val updates = moveWithinDay(listOf(first, second, otherDay), itemId = "b", delta = -1)

        assertThat(updates.map { it.id to it.orderInDay })
            .containsExactly("b" to 0, "a" to 1)
    }

    @Test
    fun `moveWithinDay at the edge changes nothing`() {
        val first = Fixtures.itineraryItem(id = "a", dayIndex = 0, orderInDay = 0)
        val second = Fixtures.itineraryItem(id = "b", dayIndex = 0, orderInDay = 1)

        assertThat(moveWithinDay(listOf(first, second), itemId = "a", delta = -1)).isEmpty()
        assertThat(moveWithinDay(listOf(first, second), itemId = "b", delta = 1)).isEmpty()
        assertThat(moveWithinDay(listOf(first, second), itemId = "missing", delta = 1)).isEmpty()
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
