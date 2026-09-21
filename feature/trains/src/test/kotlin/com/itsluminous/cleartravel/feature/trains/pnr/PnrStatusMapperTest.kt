package com.itsluminous.cleartravel.feature.trains.pnr

import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.scrape.ScrapedData
import org.junit.Test
import java.time.Instant

/**
 * Contract tests for the PURE `ScrapedData` → `TrainStatusResult` mapper, pinned to
 * the exact field/row names the `indianrail-pnr` rule emits (see the rule fixture
 * `core/scrape/src/test/resources/fixtures/indianrail-pnr/expected.json`).
 */
class PnrStatusMapperTest {
    private val fetchedAt = Instant.parse("2026-09-20T12:00:00Z")
    private val pnr = "8524317690"

    /** Verbatim shape of the shipped fixture's expected.json. */
    private val fixtureShapedData =
        ScrapedData(
            fields =
                mapOf(
                    "trainNumber" to "12951",
                    "trainName" to "MUMBAI RAJDHANI",
                    "journeyDate" to "2026-09-25",
                    "fromStation" to "MMCT",
                    "toStation" to "NDLS",
                    "reservedUpto" to "NDLS",
                    "boardingPoint" to "MMCT",
                    "journeyClass" to "3A",
                    "totalFare" to "4590",
                    "chartingStatus" to "Chart Not Prepared",
                    "remarks" to "",
                    "trainStatus" to "",
                    "queriedPnr" to "8524317690",
                ),
            rows =
                listOf(
                    mapOf(
                        "passenger" to "Passenger 1",
                        "bookingStatus" to "CNF/B4/32/GN",
                        "currentStatus" to "CNF/B4/32",
                    ),
                    mapOf(
                        "passenger" to "Passenger 2",
                        "bookingStatus" to "RAC/B1/54/GN",
                        "currentStatus" to "CNF/B1/49",
                    ),
                ),
        )

    @Test
    fun `maps the fixture shape to a full result`() {
        val result = PnrStatusMapper.map(pnr, fixtureShapedData, fetchedAt)

        assertThat(result).isNotNull()
        result!!
        assertThat(result.pnr).isEqualTo(pnr)
        assertThat(result.trainNumber).isEqualTo("12951")
        assertThat(result.trainName).isEqualTo("MUMBAI RAJDHANI")
        assertThat(result.journeyDate).isEqualTo(java.time.LocalDate.of(2026, 9, 25))
        assertThat(result.fromStation).isEqualTo("MMCT")
        assertThat(result.toStation).isEqualTo("NDLS")
        assertThat(result.travelClass).isEqualTo("3A")
        assertThat(result.fetchedAt).isEqualTo(fetchedAt)
        assertThat(result.passengers).hasSize(2)
    }

    @Test
    fun `keeps raw current status text and extracts coach and seat`() {
        val result = PnrStatusMapper.map(pnr, fixtureShapedData, fetchedAt)!!

        val first = result.passengers[0]
        assertThat(first.currentStatus).isEqualTo("CNF/B4/32")
        assertThat(first.bookingStatus).isEqualTo("CNF/B4/32/GN")
        assertThat(first.coach).isEqualTo("B4")
        assertThat(first.seatBerth).isEqualTo("32")

        val second = result.passengers[1]
        assertThat(second.coach).isEqualTo("B1")
        assertThat(second.seatBerth).isEqualTo("49")
    }

    @Test
    fun `waitlisted status without coach segments leaves coach and seat empty`() {
        val data =
            fixtureShapedData.copy(
                rows =
                    listOf(
                        mapOf(
                            "passenger" to "Passenger 1",
                            "bookingStatus" to "WL 45",
                            "currentStatus" to "WL 12",
                        ),
                    ),
            )

        val passenger = PnrStatusMapper.map(pnr, data, fetchedAt)!!.passengers.single()

        assertThat(passenger.currentStatus).isEqualTo("WL 12")
        assertThat(passenger.coach).isEmpty()
        assertThat(passenger.seatBerth).isEmpty()
    }

    @Test
    fun `chart not prepared maps to false`() {
        val result = PnrStatusMapper.map(pnr, fixtureShapedData, fetchedAt)!!

        assertThat(result.chartPrepared).isFalse()
    }

    @Test
    fun `chart prepared maps to true`() {
        val data =
            fixtureShapedData.copy(
                fields = fixtureShapedData.fields + ("chartingStatus" to "Chart Prepared"),
            )

        assertThat(PnrStatusMapper.map(pnr, data, fetchedAt)!!.chartPrepared).isTrue()
    }

    @Test
    fun `unknown chart text maps to null`() {
        val data =
            fixtureShapedData.copy(
                fields = fixtureShapedData.fields + ("chartingStatus" to "???"),
            )

        assertThat(PnrStatusMapper.map(pnr, data, fetchedAt)!!.chartPrepared).isNull()
    }

    @Test
    fun `missing rows returns null`() {
        val data = fixtureShapedData.copy(rows = emptyList())

        assertThat(PnrStatusMapper.map(pnr, data, fetchedAt)).isNull()
    }

    @Test
    fun `garbage rows with no status text returns null`() {
        val data =
            fixtureShapedData.copy(
                rows =
                    listOf(
                        mapOf("passenger" to "Passenger 1", "bookingStatus" to "", "currentStatus" to ""),
                        mapOf("unrelated" to "junk"),
                    ),
            )

        assertThat(PnrStatusMapper.map(pnr, data, fetchedAt)).isNull()
    }

    @Test
    fun `missing optional fields still map with empty train info`() {
        val data = ScrapedData(fields = emptyMap(), rows = fixtureShapedData.rows)

        val result = PnrStatusMapper.map(pnr, data, fetchedAt)!!

        assertThat(result.trainNumber).isEmpty()
        assertThat(result.trainName).isEmpty()
        assertThat(result.chartPrepared).isNull()
        assertThat(result.passengers).hasSize(2)
    }
}
