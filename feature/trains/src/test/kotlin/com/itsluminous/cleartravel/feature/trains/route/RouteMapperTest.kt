package com.itsluminous.cleartravel.feature.trains.route

import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.scrape.ScrapedData
import org.junit.Test

/**
 * Pins [RouteMapper] to the exact `erail-route` rule output shape — the row maps
 * below mirror the recorded fixture
 * `core/scrape/src/test/resources/fixtures/erail-route/expected.json` (train 22346,
 * real captured DOM, recon 2026-09-21).
 */
class RouteMapperTest {
    private val ticketId = "ticket-1"

    /** The full 7-station fixture shape for train 22346. */
    private fun fixtureData(): ScrapedData =
        ScrapedData(
            fields = mapOf("trainNumber" to "22346", "trainName" to "VANDE BHARAT EXP"),
            rows =
                listOf(
                    row("GTNR", "Gomtinagar (Lucknow)", "First", "15.20", "2", "1"),
                    row("AY", "Ayodhya Dham Jn", "17.15", "17.20", "1", "1"),
                    row("BSB", "Varanasi Jn", "19.50", "19.55", "7", "1"),
                    row("DDU", "Dd Upadhyaya Jn", "20.45", "20.50", "4", "1"),
                    row("BXR", "Buxar", "21.50", "21.52", "1", "1"),
                    row("ARA", "Ara", "22.33", "22.35", "1", "1"),
                    row("PNBE", "Patna Jn", "23.45", "Last", "8", "1"),
                ),
        )

    private fun row(
        code: String,
        name: String,
        arrival: String,
        departure: String,
        platform: String,
        day: String,
    ): Map<String, String> =
        mapOf(
            "stationCode" to code,
            "stationName" to name,
            "arrival" to arrival,
            "departure" to departure,
            "halt" to "0",
            "platform" to platform,
            "distance" to "0",
            "day" to day,
        )

    @Test
    fun `real fixture shape maps to all seven stops in order`() {
        val stops = RouteMapper.map(ticketId, fixtureData())

        assertThat(stops).isNotNull()
        assertThat(stops!!).hasSize(7)
        assertThat(stops.map { it.stationName })
            .containsExactly(
                "Gomtinagar (Lucknow) (GTNR)",
                "Ayodhya Dham Jn (AY)",
                "Varanasi Jn (BSB)",
                "Dd Upadhyaya Jn (DDU)",
                "Buxar (BXR)",
                "Ara (ARA)",
                "Patna Jn (PNBE)",
            ).inOrder()
        assertThat(stops.all { it.ticketId == ticketId }).isTrue()
    }

    @Test
    fun `sortOrder is assigned sequentially from extraction order`() {
        val stops = RouteMapper.map(ticketId, fixtureData())!!

        assertThat(stops.map { it.sortOrder }).containsExactly(0, 1, 2, 3, 4, 5, 6).inOrder()
    }

    @Test
    fun `dot-separated times normalize to colon HH-mm`() {
        val stops = RouteMapper.map(ticketId, fixtureData())!!

        assertThat(stops[1].arrival).isEqualTo("17:15")
        assertThat(stops[1].departure).isEqualTo("17:20")
        assertThat(stops[6].arrival).isEqualTo("23:45")
    }

    @Test
    fun `First and Last literals become empty times at the route ends`() {
        val stops = RouteMapper.map(ticketId, fixtureData())!!

        assertThat(stops.first().arrival).isEmpty()
        assertThat(stops.first().departure).isEqualTo("15:20")
        assertThat(stops.last().arrival).isEqualTo("23:45")
        assertThat(stops.last().departure).isEmpty()
    }

    @Test
    fun `single-digit hours are zero-padded and colon input is accepted`() {
        val data =
            ScrapedData(
                fields = emptyMap(),
                rows =
                    listOf(
                        row("A", "Alpha", "First", "9.05", "1", "1"),
                        row("B", "Beta", "9:45", "Last", "1", "1"),
                    ),
            )

        val stops = RouteMapper.map(ticketId, data)!!

        assertThat(stops[0].departure).isEqualTo("09:05")
        assertThat(stops[1].arrival).isEqualTo("09:45")
    }

    @Test
    fun `out-of-range or garbage time text maps to empty`() {
        val data =
            ScrapedData(
                fields = emptyMap(),
                rows =
                    listOf(
                        row("A", "Alpha", "25.99", "soon", "1", "1"),
                        row("B", "Beta", "not a time", "99:99", "1", "1"),
                    ),
            )

        val stops = RouteMapper.map(ticketId, data)!!

        assertThat(stops[0].arrival).isEmpty()
        assertThat(stops[0].departure).isEmpty()
        assertThat(stops[1].arrival).isEmpty()
        assertThat(stops[1].departure).isEmpty()
    }

    @Test
    fun `day column parses and a malformed day cell carries the running day forward`() {
        val data =
            ScrapedData(
                fields = emptyMap(),
                rows =
                    listOf(
                        row("A", "Alpha", "First", "17.00", "1", "1"),
                        row("B", "Beta", "08.35", "Last", "1", "2"),
                        row("C", "Gamma", "10.00", "10.05", "1", "??"),
                    ),
            )

        val stops = RouteMapper.map(ticketId, data)!!

        assertThat(stops[0].day).isEqualTo(1)
        assertThat(stops[1].day).isEqualTo(2)
        // "??" doesn't parse — the stop keeps the running day instead of resetting.
        assertThat(stops[2].day).isEqualTo(2)
    }

    @Test
    fun `station name falls back to the station code`() {
        val data =
            ScrapedData(
                fields = emptyMap(),
                rows =
                    listOf(
                        mapOf("stationCode" to "GTNR", "stationName" to "", "departure" to "15.20"),
                        mapOf("stationCode" to "PNBE", "stationName" to "", "arrival" to "23.45"),
                    ),
            )

        val stops = RouteMapper.map(ticketId, data)!!

        assertThat(stops.map { it.stationName }).containsExactly("GTNR", "PNBE").inOrder()
    }

    @Test
    fun `garbage rows with no station at all yield null`() {
        val data =
            ScrapedData(
                fields = emptyMap(),
                rows = listOf(mapOf("foo" to "bar"), mapOf("stationName" to " ")),
            )

        assertThat(RouteMapper.map(ticketId, data)).isNull()
    }

    @Test
    fun `empty rows yield null`() {
        assertThat(RouteMapper.map(ticketId, ScrapedData(fields = emptyMap()))).isNull()
    }

    @Test
    fun `a single usable stop is not a route and yields null`() {
        val data =
            ScrapedData(
                fields = emptyMap(),
                rows =
                    listOf(
                        row("A", "Alpha", "First", "15.20", "1", "1"),
                        mapOf("stationName" to "", "stationCode" to ""),
                    ),
            )

        assertThat(RouteMapper.map(ticketId, data)).isNull()
    }

    @Test
    fun `platform text is kept verbatim`() {
        val stops = RouteMapper.map(ticketId, fixtureData())!!

        assertThat(stops[2].platform).isEqualTo("7")
        assertThat(stops[6].platform).isEqualTo("8")
    }

    // ---- ixigo-route shape (PRIMARY source, ADR-019) --------------------------

    /** Rows exactly as the `ixigo-route` rule emits them (train 22346 capture). */
    private fun ixigoRow(
        code: String,
        name: String,
        arrival: String,
        departure: String,
        halt: String,
        distance: String,
        platform: String,
        day: String,
    ): Map<String, String> =
        mapOf(
            "stationCode" to code,
            "stationName" to name,
            "arrival" to arrival,
            "departure" to departure,
            "halt" to halt,
            "distance" to distance,
            "platform" to platform,
            "day" to day,
        )

    private fun ixigoFixtureData(): ScrapedData =
        ScrapedData(
            fields = mapOf("trainNumber" to "22346", "trainName" to "Vande Bharat Exp"),
            rows =
                listOf(
                    ixigoRow("GTNR", "Gomati Nagar", "starts", "15:20", "-", "0", "2", "1"),
                    ixigoRow("AY", "Ayodhya", "17:15", "17:20", "5min", "127 km", "1", "1"),
                    ixigoRow("BSB", "Varanasi Jn", "19:50", "19:55", "5min", "316 km", "7", "1"),
                    ixigoRow("DDU", "Dd Upadhyaya Jn", "20:45", "20:50", "5min", "333 km", "4", "1"),
                    ixigoRow("BXR", "Buxar", "21:50", "21:52", "2min", "427 km", "1", "1"),
                    ixigoRow("ARA", "Ara Jn", "22:33", "22:35", "2min", "495 km", "1", "1"),
                    ixigoRow("PNBE", "Patna Jn", "23:45", "ends", "-", "544 km", "8", "1"),
                ),
        )

    @Test
    fun `ixigo fixture shape maps all seven stops with colon times kept`() {
        val stops = RouteMapper.map(ticketId, ixigoFixtureData())!!

        assertThat(stops).hasSize(7)
        assertThat(stops[1].arrival).isEqualTo("17:15")
        assertThat(stops[1].departure).isEqualTo("17:20")
        assertThat(stops.map { it.sortOrder }).containsExactly(0, 1, 2, 3, 4, 5, 6).inOrder()
    }

    @Test
    fun `ixigo starts and ends literals become empty times at the route ends`() {
        val stops = RouteMapper.map(ticketId, ixigoFixtureData())!!

        assertThat(stops.first().arrival).isEmpty()
        assertThat(stops.first().departure).isEqualTo("15:20")
        assertThat(stops.last().arrival).isEqualTo("23:45")
        assertThat(stops.last().departure).isEmpty()
    }

    @Test
    fun `ixigo dash platform placeholder maps to empty`() {
        val data =
            ScrapedData(
                fields = emptyMap(),
                rows =
                    listOf(
                        ixigoRow("GJD", "Gujhandi", "10:00", "10:02", "2min", "10 km", "-", "1"),
                        ixigoRow("GAP", "Gurpa", "10:20", "10:22", "2min", "20 km", "3", "1"),
                    ),
            )

        val stops = RouteMapper.map(ticketId, data)!!

        assertThat(stops[0].platform).isEmpty()
        assertThat(stops[1].platform).isEqualTo("3")
    }

    @Test
    fun `ixigo multi-day rows keep the source day column 1 through 3`() {
        val data =
            ScrapedData(
                fields = emptyMap(),
                rows =
                    listOf(
                        ixigoRow("KOAA", "Kolkata Chitpur", "starts", "11:45", "-", "0", "1", "1"),
                        ixigoRow("BBU", "Bhabua Road", "23:59", "00:01", "2min", "600 km", "1", "1"),
                        ixigoRow("DDU", "Dd Upadhyaya Jn", "01:25", "01:35", "10min", "700 km", "4", "2"),
                        ixigoRow("YJUD", "Yamunanagar Jud", "00:06", "00:08", "2min", "1500 km", "1", "3"),
                        ixigoRow("JAT", "Jammu Tawi", "05:45", "ends", "-", "1800 km", "2", "3"),
                    ),
            )

        val stops = RouteMapper.map(ticketId, data)!!

        assertThat(stops.map { it.day }).containsExactly(1, 1, 2, 3, 3).inOrder()
    }

    // ---- day inference for the day-less erail mobile fallback (ADR-019) -------

    /** Rows as the erail-route v2 rule emits them: NO day key at all. */
    private fun erailMobileRow(
        name: String,
        arrival: String,
        departure: String,
    ): Map<String, String> =
        mapOf(
            "stationName" to name,
            "arrival" to arrival,
            "departure" to departure,
            "distance" to "0",
            "platform" to "1",
        )

    @Test
    fun `day-less rows infer day increments at each midnight crossing`() {
        val data =
            ScrapedData(
                fields = emptyMap(),
                rows =
                    listOf(
                        erailMobileRow("Kolkata Chitpur", "First", "11.45"),
                        erailMobileRow("Bhabua Road", "23.50", "23.52"),
                        erailMobileRow("Dd Upadhyaya Jn", "01.25", "01.35"),
                        erailMobileRow("Ludhiana Jn", "20.10", "20.20"),
                        erailMobileRow("Yamunanagar Jud", "00.06", "00.08"),
                        erailMobileRow("Jammu Tawi", "05.45", "Last"),
                    ),
            )

        val stops = RouteMapper.map(ticketId, data)!!

        assertThat(stops.map { it.day }).containsExactly(1, 1, 2, 2, 3, 3).inOrder()
    }

    @Test
    fun `day-less same-day route stays day 1 throughout`() {
        val data =
            ScrapedData(
                fields = emptyMap(),
                rows =
                    listOf(
                        erailMobileRow("Gomtinagar (Lucknow)", "First", "15.20"),
                        erailMobileRow("Varanasi Jn", "19.50", "19.55"),
                        erailMobileRow("Patna Jn", "23.45", "Last"),
                    ),
            )

        val stops = RouteMapper.map(ticketId, data)!!

        assertThat(stops.map { it.day }).containsExactly(1, 1, 1).inOrder()
    }
}
