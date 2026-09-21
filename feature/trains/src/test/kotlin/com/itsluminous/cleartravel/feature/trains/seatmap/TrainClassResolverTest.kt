package com.itsluminous.cleartravel.feature.trains.seatmap

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

class TrainClassResolverTest {
    @Test
    fun `coach code prefixes map to their layout class`() {
        val cases =
            mapOf(
                "S1" to "SL",
                "s12" to "SL",
                "B4" to "3A",
                "M1" to "3A",
                "A1" to "2A",
                "H1" to "1A",
                "HA1" to "1A",
                "C3" to "CC",
                "E1" to "EC",
                "D2" to "2S",
                "GN" to "GN",
                "GS" to "GN",
                "SLR" to "GN",
                "UR" to "GN",
            )
        for ((code, expected) in cases) {
            assertWithMessage(code).that(TrainClassResolver.fromCoachCode(code)).isEqualTo(expected)
        }
    }

    @Test
    fun `non-passenger coaches and unknown codes resolve to null`() {
        for (code in listOf("EN", "EOG", "PC", "LOCO", "", "  ", "12", "??", "X9")) {
            assertWithMessage(code).that(TrainClassResolver.fromCoachCode(code)).isNull()
        }
    }

    @Test
    fun `travel class resolves directly with aliases`() {
        assertThat(TrainClassResolver.fromTravelClass("3A")).isEqualTo("3A")
        assertThat(TrainClassResolver.fromTravelClass("sl")).isEqualTo("SL")
        assertThat(TrainClassResolver.fromTravelClass("3E")).isEqualTo("3A")
        assertThat(TrainClassResolver.fromTravelClass("FC")).isEqualTo("1A")
        assertThat(TrainClassResolver.fromTravelClass("EA")).isEqualTo("EC")
        assertThat(TrainClassResolver.fromTravelClass("ZZ")).isNull()
        assertThat(TrainClassResolver.fromTravelClass("")).isNull()
    }

    @Test
    fun `resolve prefers the coach code and falls back to the ticket class`() {
        assertThat(TrainClassResolver.resolve("B4", "SL")).isEqualTo("3A")
        assertThat(TrainClassResolver.resolve("", "SL")).isEqualTo("SL")
        assertThat(TrainClassResolver.resolve("PC", "SL")).isEqualTo("SL")
        assertThat(TrainClassResolver.resolve("", "")).isNull()
    }
}
