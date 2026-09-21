package com.itsluminous.cleartravel.feature.trains.seatmap

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SeatBerthParserTest {
    @Test
    fun `bare and suffixed berth numbers parse`() {
        assertThat(SeatBerthParser.berthNumber("32")).isEqualTo(32)
        assertThat(SeatBerthParser.berthNumber("32 LB")).isEqualTo(32)
        assertThat(SeatBerthParser.berthNumber("LB 32")).isEqualTo(32)
        assertThat(SeatBerthParser.berthNumber("12A")).isEqualTo(12)
        assertThat(SeatBerthParser.berthNumber(" 7 ")).isEqualTo(7)
    }

    @Test
    fun `an embedded coach code is skipped in favour of the berth`() {
        assertThat(SeatBerthParser.berthNumber("B4 32")).isEqualTo(32)
        assertThat(SeatBerthParser.berthNumber("B4-32")).isEqualTo(32)
        assertThat(SeatBerthParser.berthNumber("S1/12")).isEqualTo(12)
        assertThat(SeatBerthParser.berthNumber("HA1 3 LB")).isEqualTo(3)
    }

    @Test
    fun `no berth yields null`() {
        assertThat(SeatBerthParser.berthNumber("")).isNull()
        assertThat(SeatBerthParser.berthNumber("WL")).isNull()
        assertThat(SeatBerthParser.berthNumber("B4")).isNull()
        assertThat(SeatBerthParser.berthNumber("0")).isNull()
        assertThat(SeatBerthParser.berthNumber("RAC")).isNull()
    }
}
