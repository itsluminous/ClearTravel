package com.itsluminous.cleartravel.feature.flights.checkin

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File
import java.time.Instant

class CheckInWindowTest {
    private val rules =
        CheckInRules(
            version = 1,
            defaultWindow = CheckInWindowSpec(opensHoursBefore = 48, closesHoursBefore = 1),
            airlines =
                mapOf(
                    "DL" to
                        AirlineCheckInInfo(
                            name = "Delta Air Lines",
                            opensHoursBefore = 24,
                            closesHoursBefore = 1,
                            checkInUrl = "https://www.delta.com/checkin",
                        ),
                ),
        )

    private val departure = Instant.parse("2026-09-22T10:00:00Z")

    @Test
    fun `default window applies to unknown airlines`() {
        val window = computeCheckInWindow(rules, "ZZ", departure)!!

        assertThat(window.opensAt).isEqualTo(Instant.parse("2026-09-20T10:00:00Z"))
        assertThat(window.closesAt).isEqualTo(Instant.parse("2026-09-22T09:00:00Z"))
    }

    @Test
    fun `per-airline override wins`() {
        val window = computeCheckInWindow(rules, "DL", departure)!!

        assertThat(window.opensAt).isEqualTo(Instant.parse("2026-09-21T10:00:00Z"))
    }

    @Test
    fun `airline lookup is case-insensitive and trimmed`() {
        assertThat(rules.windowFor(" dl ")).isEqualTo(CheckInWindowSpec(24, 1))
        assertThat(rules.checkInUrl("dl")).isEqualTo("https://www.delta.com/checkin")
        assertThat(rules.airlineName("dl")).isEqualTo("Delta Air Lines")
    }

    @Test
    fun `no scheduled departure means no window`() {
        assertThat(computeCheckInWindow(rules, "DL", null)).isNull()
    }

    @Test
    fun `isOpenAt is open-inclusive close-exclusive`() {
        val window = computeCheckInWindow(rules, "DL", departure)!!

        assertThat(window.isOpenAt(window.opensAt)).isTrue()
        assertThat(window.isOpenAt(window.opensAt.minusSeconds(1))).isFalse()
        assertThat(window.isOpenAt(window.closesAt)).isFalse()
        assertThat(window.isOpenAt(window.closesAt.minusSeconds(1))).isTrue()
    }

    @Test
    fun `parser degrades to EMPTY on garbage`() {
        assertThat(CheckInRulesParser.parse("not json at all")).isEqualTo(CheckInRules.EMPTY)
    }
}

/**
 * Fixture test for the SHIPPED data file (ADR-003: a data file without its own test
 * is a review-blocking omission) — parses the real asset from the module directory.
 */
class CheckInWindowsAssetTest {
    private fun assetFile(): File =
        listOf(
            File("src/main/assets/checkin-windows.json"),
            File("feature/flights/src/main/assets/checkin-windows.json"),
        ).firstOrNull(File::isFile) ?: error("checkin-windows.json asset not found from ${File(".").absolutePath}")

    @Test
    fun `shipped asset parses and is complete`() {
        val rules = CheckInRulesParser.parse(assetFile().readText())

        assertThat(rules.version).isAtLeast(1)
        // All 13 spec airlines are present.
        val expected = listOf("6E", "AI", "SG", "QP", "IX", "EK", "QR", "SQ", "EY", "DL", "AA", "LH", "CX")
        assertThat(rules.airlines.keys).containsAtLeastElementsIn(expected)
        for ((iata, info) in rules.airlines) {
            assertThat(iata).matches("[A-Z0-9]{2}")
            assertThat(info.name).isNotEmpty()
            assertThat(info.opensHoursBefore).isGreaterThan(info.closesHoursBefore)
            assertThat(info.checkInUrl).startsWith("https://")
        }
    }
}
