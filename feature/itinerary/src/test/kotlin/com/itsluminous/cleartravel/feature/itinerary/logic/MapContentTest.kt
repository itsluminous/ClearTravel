package com.itsluminous.cleartravel.feature.itinerary.logic

import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.model.CommuteMode
import com.itsluminous.cleartravel.core.model.ItineraryItemType
import com.itsluminous.cleartravel.core.testing.Fixtures
import org.junit.Test

class MapContentTest {
    @Test
    fun `skips items without coordinates`() {
        val items =
            listOf(
                Fixtures.itineraryItem(latitude = null, longitude = null, name = "No coords"),
                Fixtures.itineraryItem(latitude = 12.0, longitude = null, name = "Half coords"),
                Fixtures.itineraryItem(latitude = 12.0, longitude = 77.0, name = "Located"),
            )

        val content = buildTripMapContent(items)

        assertThat(content.markers).hasSize(1)
        assertThat(
            content.markers
                .single()
                .item.name,
        ).isEqualTo("Located")
        assertThat(content.paths).isEmpty()
    }

    @Test
    fun `markers are numbered 1-based per day for places only`() {
        val items =
            listOf(
                Fixtures.itineraryItem(dayIndex = 0, orderInDay = 0, latitude = 1.0, longitude = 1.0),
                Fixtures.itineraryItem(
                    dayIndex = 0,
                    orderInDay = 1,
                    latitude = 2.0,
                    longitude = 2.0,
                    type = ItineraryItemType.COMMUTE,
                    commuteMode = CommuteMode.CAB,
                    fromName = "A",
                    toName = "B",
                ),
                Fixtures.itineraryItem(dayIndex = 0, orderInDay = 2, latitude = 3.0, longitude = 3.0),
                Fixtures.itineraryItem(dayIndex = 1, orderInDay = 0, latitude = 4.0, longitude = 4.0),
            )

        val markers = buildTripMapContent(items).markers

        assertThat(markers.map { it.dayIndex to it.numberInDay })
            .containsExactly(0 to 1, 0 to 2, 1 to 1)
            .inOrder()
        assertThat(markers.map { it.item.type }).doesNotContain(ItineraryItemType.COMMUTE)
    }

    @Test
    fun `each day with two or more located items gets an ordered polyline`() {
        val items =
            listOf(
                Fixtures.itineraryItem(dayIndex = 0, orderInDay = 1, latitude = 2.0, longitude = 2.0),
                Fixtures.itineraryItem(dayIndex = 0, orderInDay = 0, latitude = 1.0, longitude = 1.0),
                Fixtures.itineraryItem(dayIndex = 1, orderInDay = 0, latitude = 9.0, longitude = 9.0),
            )

        val paths = buildTripMapContent(items).paths

        // Day 1 has a single located item — no path for it.
        assertThat(paths).hasSize(1)
        assertThat(paths.single().dayIndex).isEqualTo(0)
        assertThat(paths.single().points)
            .containsExactly(MapPoint(1.0, 1.0), MapPoint(2.0, 2.0))
            .inOrder()
    }

    @Test
    fun `empty items produce empty content`() {
        val content = buildTripMapContent(emptyList())

        assertThat(content.isEmpty).isTrue()
    }
}

/** ADR-048: which pane the map view shows — markers, the locating notice, or the empty notice. */
class TripMapPaneTest {
    private val empty = TripMapContent(emptyList(), emptyList())
    private val withMarker =
        buildTripMapContent(
            listOf(Fixtures.itineraryItem(latitude = 26.87, longitude = 80.91)),
        )

    @Test
    fun `empty content while locating shows the locating pane`() {
        assertThat(tripMapPane(empty, locating = true)).isEqualTo(TripMapPane.LOCATING)
    }

    @Test
    fun `empty content with nothing running shows the empty notice`() {
        assertThat(tripMapPane(empty, locating = false)).isEqualTo(TripMapPane.EMPTY)
    }

    @Test
    fun `any marker shows the map whether or not a backfill is running`() {
        assertThat(tripMapPane(withMarker, locating = true)).isEqualTo(TripMapPane.MAP)
        assertThat(tripMapPane(withMarker, locating = false)).isEqualTo(TripMapPane.MAP)
    }
}
