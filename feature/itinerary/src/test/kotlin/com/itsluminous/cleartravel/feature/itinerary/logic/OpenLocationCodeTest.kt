package com.itsluminous.cleartravel.feature.itinerary.logic

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * ADR-048: the plus-code implementation against Google's published test vectors
 * (`test_data/decoding.csv` and `shortCodeTests.csv` from github.com/google/
 * open-location-code, copied under `src/test/resources/maps-links/`), plus the user's
 * real short code anchored to Lucknow.
 */
class OpenLocationCodeTest {
    @Test
    fun `decodes every reference vector`() {
        val rows = fixtureRows("olc-decoding.csv")
        assertThat(rows.size).isGreaterThan(400)
        rows.forEach { row ->
            val code = row[0]
            val area = checkNotNull(OpenLocationCode.decode(code)) { "could not decode $code" }
            assertThat(area.latitudeLo).isWithin(TOLERANCE).of(row[2].toDouble())
            assertThat(area.longitudeLo).isWithin(TOLERANCE).of(row[3].toDouble())
            assertThat(area.latitudeHi).isWithin(TOLERANCE).of(row[4].toDouble())
            assertThat(area.longitudeHi).isWithin(TOLERANCE).of(row[5].toDouble())
        }
    }

    @Test
    fun `recovers every reference short code to its full code`() {
        val rows = fixtureRows("olc-short-codes.csv").filter { it[4] == "R" || it[4] == "B" }
        assertThat(rows).isNotEmpty()
        rows.forEach { row ->
            val (full, lat, lng, short) = row
            assertThat(OpenLocationCode.recoverNearest(short, lat.toDouble(), lng.toDouble())).isEqualTo(full)
        }
    }

    /**
     * The user's "VW6G+7P3 The Residency, Lucknow" — recovered against each Nominatim
     * answer for "Lucknow" (26.8381/80.9346, 26.8312/80.9174) and decoded. Expected
     * values come from the reference Python `openlocationcode` package:
     * `recoverNearest('VW6G+7P3', 26.8381, 80.9346)` → `7MR2VW6G+7P3`, centre
     * 26.8606375, 80.926796875.
     */
    @Test
    fun `the Residency short code anchors to Lucknow`() {
        listOf(26.8381 to 80.9346, 26.8312 to 80.9174, 26.85 to 80.95).forEach { (lat, lng) ->
            val full = OpenLocationCode.recoverNearest("VW6G+7P3", lat, lng)
            assertThat(full).isEqualTo("7MR2VW6G+7P3")
            val area = checkNotNull(OpenLocationCode.decode(full!!))
            assertThat(area.latitude).isWithin(1e-9).of(26.8606375)
            assertThat(area.longitude).isWithin(1e-9).of(80.926796875)
        }
    }

    @Test
    fun `validity and shape checks`() {
        assertThat(OpenLocationCode.isValid("VW6G+7P3")).isTrue()
        assertThat(OpenLocationCode.isShort("VW6G+7P3")).isTrue()
        assertThat(OpenLocationCode.isFull("VW6G+7P3")).isFalse()
        assertThat(OpenLocationCode.isFull("7MR2VW6G+7P3")).isTrue()
        assertThat(OpenLocationCode.isValid("VW6G+7")).isFalse()
        assertThat(OpenLocationCode.isValid("ABCD+EF")).isFalse()
        assertThat(OpenLocationCode.isValid("7MR2VW6G7P3")).isFalse()
        assertThat(OpenLocationCode.decode("VW6G+7P3")).isNull()
        assertThat(OpenLocationCode.recoverNearest("nonsense", 0.0, 0.0)).isNull()
        assertThat(OpenLocationCode.recoverNearest("7MR2VW6G+7P3", 0.0, 0.0)).isEqualTo("7MR2VW6G+7P3")
    }

    @Test
    fun `the code pattern finds short and full codes inside place text`() {
        assertThat(OpenLocationCode.CODE_PATTERN.find("VW6G+7P3 The Residency, Lucknow")?.value).isEqualTo("VW6G+7P3")
        assertThat(OpenLocationCode.CODE_PATTERN.find("at 7MR2VW6G+7P3 today")?.value).isEqualTo("7MR2VW6G+7P3")
        assertThat(OpenLocationCode.CODE_PATTERN.find("Royal Cafe, 51, Mahatma Gandhi Marg")).isNull()
    }

    private fun fixtureRows(name: String): List<List<String>> =
        checkNotNull(javaClass.getResourceAsStream("/maps-links/$name")) { "missing fixture $name" }
            .bufferedReader()
            .readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { line -> line.split(',').map(String::trim) }

    private companion object {
        const val TOLERANCE = 1e-10
    }
}
