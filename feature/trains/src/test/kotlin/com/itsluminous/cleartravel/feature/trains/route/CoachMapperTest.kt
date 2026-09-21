package com.itsluminous.cleartravel.feature.trains.route

import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.scrape.ScrapedData
import org.junit.Test

class CoachMapperTest {
    private fun data(codes: List<String>?): ScrapedData =
        ScrapedData(
            fields = mapOf("trainNumber" to "22346"),
            rows = listOf(mapOf("stationName" to "A"), mapOf("stationName" to "B")),
            extraRows = if (codes == null) emptyMap() else mapOf("coaches" to codes.map { mapOf("code" to it) }),
        )

    @Test
    fun `maps the 22346 Vande Bharat capture shape in rake order with trimmed codes`() {
        // Exactly what the ixigo-route fixture yields for train 22346 (trailing spaces included).
        val coaches =
            CoachMapper.map("t1", data(listOf("EN ", "C1 ", "C2 ", "C3 ", "C4 ", "C5 ", "E1 ", "C6 ", "C7 ")))

        assertThat(coaches).isNotNull()
        assertThat(coaches!!.map { it.code })
            .containsExactly("EN", "C1", "C2", "C3", "C4", "C5", "E1", "C6", "C7")
            .inOrder()
        assertThat(coaches.map { it.sortOrder }).isEqualTo((0..8).toList())
        assertThat(coaches.map { it.ticketId }.distinct()).containsExactly("t1")
    }

    @Test
    fun `maps the 13151 mixed-composition shape keeping duplicate GN coaches`() {
        val codes =
            listOf(
                "EN",
                "GN",
                "GN",
                "S1",
                "S2",
                "S3",
                "S4",
                "S5",
                "S6",
                "S7",
                "PC",
                "M1",
                "B1",
                "B2",
                "B3",
                "B4",
                "B5",
                "A1",
                "GN",
                "GN",
            )

        val coaches = CoachMapper.map("t1", data(codes))

        assertThat(coaches!!.map { it.code }).isEqualTo(codes)
        assertThat(coaches.count { it.code == "GN" }).isEqualTo(4)
        assertThat(coaches.last().sortOrder).isEqualTo(19)
    }

    @Test
    fun `absent coaches section maps to null so stored coaches are kept`() {
        assertThat(CoachMapper.map("t1", data(null))).isNull()
    }

    @Test
    fun `present but empty or blank-only section maps to null`() {
        assertThat(CoachMapper.map("t1", data(emptyList()))).isNull()
        assertThat(CoachMapper.map("t1", data(listOf(" ", "")))).isNull()
    }

    @Test
    fun `blank rows are dropped and positions renumbered contiguously`() {
        val coaches = CoachMapper.map("t1", data(listOf("EN", "", "s1", " b4 ")))

        assertThat(coaches!!.map { it.code }).containsExactly("EN", "S1", "B4").inOrder()
        assertThat(coaches.map { it.sortOrder }).containsExactly(0, 1, 2).inOrder()
    }
}
