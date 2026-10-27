package com.itsluminous.cleartravel.core.ocr.extract

import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.ocr.ExtractionConfidence
import com.itsluminous.cleartravel.core.ocr.model.ExtractedField
import com.itsluminous.cleartravel.core.ocr.model.ExtractedFlight
import com.itsluminous.cleartravel.core.ocr.model.ExtractedPassenger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import java.io.File

/**
 * ADR-003 guard for the bundled `flight-document-rules.json` (ADR-045): it loads, every
 * alias compiles, and its airline table stays identical to the SMS rules asset of
 * `feature:flights` (one source of truth for airline names, two modules that cannot
 * depend on each other — the test is the link).
 */
class FlightDocumentRulesTest {
    private val rules = FlightDocumentRules.BUNDLED

    @Test
    fun `bundled rules load with airlines and stop-words`() {
        assertThat(rules.version).isAtLeast(1)
        assertThat(rules.airlines).isNotEmpty()
        assertThat(rules.flightCodeStopwords).contains("ON")
        assertThat(rules.iataStopwords).contains("PNR")
        assertThat(rules.aliasPatterns).isNotEmpty() // every alias compiled
    }

    @Test
    fun `longest alias wins so Air India Express is never Air India`() {
        assertThat(rules.airlineByAlias("AIR INDIA EXPRESS IX-2019")).isEqualTo("IX")
        assertThat(rules.airlineByAlias("AIR INDIA AI 0865")).isEqualTo("AI")
        assertThat(rules.airlineByAlias("INDIGO 6E 6114")).isEqualTo("6E")
        assertThat(rules.airlineByAlias("NOTHING HERE")).isNull()
    }

    @Test
    fun `airline table matches the feature-flights SMS rules asset`() {
        val smsAsset =
            listOf(
                File("../../feature/flights/src/main/assets/airline-sms-rules.json"),
                File("feature/flights/src/main/assets/airline-sms-rules.json"),
            ).firstOrNull { it.exists() } ?: return // module run from an unexpected cwd: nothing to compare
        val sms = Json.parseToJsonElement(smsAsset.readText()).jsonObject["airlines"]!!.jsonObject
        val smsTable = sms.mapValues { (_, info) -> info.jsonObject["name"]!!.jsonPrimitive.content }

        assertThat(rules.airlines.mapValues { it.value.name }).containsExactlyEntriesIn(smsTable)
    }

    @Test
    fun `a corrupt rules text parses to nothing without throwing`() {
        val parsed = runCatching { FlightDocumentRules.parse("not json") }
        assertThat(parsed.isFailure).isTrue() // the loader catches this and falls back to an empty table
        assertThat(FlightTokens(FlightDocumentRules(version = 0)).findAll("AIR INDIA EXPRESS 2019")).isEmpty()
        assertThat(FlightTokens(FlightDocumentRules(version = 0)).findAll("IX 2019").single().carrier).isEqualTo("IX")
    }
}

class FlightTokensTest {
    private val tokens = FlightTokens()

    private fun keys(line: String) = tokens.findAll(line.uppercase()).map { it.key }

    @Test
    fun `labeled bare and alias forms are recognised`() {
        assertThat(keys("Flight 6E 2345")).containsExactly("6E2345")
        assertThat(tokens.findAll("FLIGHT 6E 2345").single().confidence).isEqualTo(ExtractionConfidence.HIGH)
        assertThat(keys("AI 0503")).containsExactly("AI503")
        assertThat(keys("Air India Express 2019")).containsExactly("IX2019")
        assertThat(keys("Air India Express IX-2019")).containsExactly("IX2019")
    }

    @Test
    fun `an OCR-garbled code next to an alias defers to the alias`() {
        assertThat(keys("LKO - BLR, Air India Express lX-1542")).containsExactly("IX1542")
    }

    @Test
    fun `durations weights amounts aircraft and stop-words are not flights`() {
        assertThat(keys("Duration : 02h 35m")).isEmpty()
        assertThat(keys("2h 35m")).isEmpty()
        assertThat(keys("7 Kgs 15 Kgs")).isEmpty()
        assertThat(keys("Base fare Z14723.00")).isEmpty()
        assertThat(keys("Boeing 737 Max 8 Airbus A320")).isEmpty()
        assertThat(keys("Total Amount Paid RS 4520")).isEmpty()
        assertThat(keys("ON 29 Oct")).isEmpty()
    }
}

class FlightSegmentsTest {
    private fun flight(
        carrier: String,
        number: String,
        date: String? = null,
        seat: String? = null,
    ) = ExtractedFlight(
        carrier = ExtractedField.of(carrier, ExtractionConfidence.HIGH),
        flightNumber = ExtractedField.of(number, ExtractionConfidence.HIGH),
        flightDate = ExtractedField.of(date, ExtractionConfidence.HIGH),
        passengers = ExtractedPassenger.single(seat = ExtractedField.of(seat, ExtractionConfidence.HIGH)),
    )

    @Test
    fun `same flight and date collapse and fill blanks`() {
        val merged = FlightSegments.dedupe(listOf(flight("IX", "2019", "2026-10-29"), flight("IX", "02019", "2026-10-29", seat = "12A")))
        assertThat(merged).hasSize(1)
        assertThat(merged.single().seat.value).isEqualTo("12A")
    }

    @Test
    fun `an undated mention folds into the dated one`() {
        assertThat(FlightSegments.dedupe(listOf(flight("IX", "2019", "2026-10-29"), flight("IX", "2019")))).hasSize(1)
        assertThat(
            FlightSegments
                .dedupe(listOf(flight("IX", "2019"), flight("IX", "2019", "2026-10-29")))
                .single()
                .flightDate.value,
        ).isEqualTo("2026-10-29")
    }

    @Test
    fun `different dates or flights stay apart`() {
        assertThat(FlightSegments.dedupe(listOf(flight("IX", "2019", "2026-10-29"), flight("IX", "2019", "2026-10-31")))).hasSize(2)
        assertThat(FlightSegments.dedupe(listOf(flight("IX", "2019", "2026-10-29"), flight("IX", "1542", "2026-10-29")))).hasSize(2)
    }

    // ---- ADR-047: passengers merge when mentions fold -------------------------------

    private fun pass(
        name: String?,
        seat: String?,
        sequence: String? = null,
        pnr: String? = null,
    ) = ExtractedFlight(
        carrier = ExtractedField.of("6E", ExtractionConfidence.HIGH),
        flightNumber = ExtractedField.of("2345", ExtractionConfidence.HIGH),
        flightDate = ExtractedField.of("2025-09-20", ExtractionConfidence.HIGH),
        pnr = ExtractedField.of(pnr, ExtractionConfidence.HIGH),
        passengers =
            ExtractedPassenger.single(
                name = ExtractedField.of(name, ExtractionConfidence.HIGH),
                seat = ExtractedField.of(seat, ExtractionConfidence.HIGH),
                sequenceNumber = ExtractedField.of(sequence, ExtractionConfidence.HIGH),
            ),
    )

    @Test
    fun `the same person mentioned on another page stays one passenger`() {
        val merged = FlightSegments.dedupe(listOf(pass("RAHUL SHARMA", "12A", "25"), pass("Sharma/Rahul", null, null))).single()
        assertThat(merged.passengers).hasSize(1)
        assertThat(
            merged.passengers
                .single()
                .seat.value,
        ).isEqualTo("12A")
        assertThat(merged.seat.value).isEqualTo("12A")
    }

    @Test
    fun `a second pass for the same flight with another name becomes passenger two`() {
        val merged = FlightSegments.dedupe(listOf(pass("RAHUL SHARMA", "12A", "25"), pass("PRIYA SHARMA", "12B", "26"))).single()
        assertThat(merged.passengers.map { it.name.value }).containsExactly("RAHUL SHARMA", "PRIYA SHARMA").inOrder()
        assertThat(merged.passengers.map { it.seat.value }).containsExactly("12A", "12B").inOrder()
        assertThat(merged.passengers.map { it.sequenceNumber.value }).containsExactly("25", "26").inOrder()
        // Derived views keep reading passenger #1.
        assertThat(merged.passengerName.value).isEqualTo("RAHUL SHARMA")
        assertThat(merged.seat.value).isEqualTo("12A")
    }

    @Test
    fun `a nameless mention with the same seat fills the named passenger`() {
        val merged = FlightSegments.dedupe(listOf(pass("RAHUL SHARMA", "12A"), pass(null, "12A", "25"))).single()
        assertThat(merged.passengers).hasSize(1)
        assertThat(
            merged.passengers
                .single()
                .sequenceNumber.value,
        ).isEqualTo("25")
    }

    @Test
    fun `a nameless mention with no seat is nobody new`() {
        val merged = FlightSegments.dedupe(listOf(pass("RAHUL SHARMA", "12A"), pass(null, null))).single()
        assertThat(merged.passengers).hasSize(1)
    }

    @Test
    fun `passengers keep their own locator when the stacked passes print two`() {
        val merged =
            FlightSegments
                .dedupe(
                    listOf(pass("RAHUL SHARMA", "12A", pnr = "AAA111"), pass("PRIYA SHARMA", "12B", pnr = "BBB222")),
                ).single()
        assertThat(merged.pnr.value).isEqualTo("AAA111")
        assertThat(merged.passengers[0].pnr.isPresent).isFalse()
        assertThat(merged.passengers[1].pnr.value).isEqualTo("BBB222")
    }
}

class ExtractedPassengerTest {
    private fun p(
        name: String? = null,
        seat: String? = null,
        sequence: String? = null,
    ) = ExtractedPassenger(
        name = ExtractedField.of(name, ExtractionConfidence.MEDIUM),
        seat = ExtractedField.of(seat, ExtractionConfidence.MEDIUM),
        sequenceNumber = ExtractedField.of(sequence, ExtractionConfidence.MEDIUM),
    )

    @Test
    fun `single yields nobody for an all-blank source`() {
        assertThat(ExtractedPassenger.single()).isEmpty()
        assertThat(ExtractedPassenger.single(seat = ExtractedField.of("1A", ExtractionConfidence.LOW))).hasSize(1)
    }

    @Test
    fun `merge matches by name first and appends the rest in order`() {
        val merged =
            ExtractedPassenger.merge(
                listOf(p("PRAKASH KUMAR", "12A")),
                listOf(p("Kumar/Prakash", "99Z"), p("BANDANA KUMARI", "12B"), p()),
            )
        assertThat(merged.map { it.name.value }).containsExactly("PRAKASH KUMAR", "BANDANA KUMARI").inOrder()
        assertThat(merged[0].seat.value).isEqualTo("12A")
    }

    @Test
    fun `json round trip keeps the passengers array and the flat passenger one fields`() {
        val flight =
            ExtractedFlight(
                carrier = ExtractedField.of("IX", ExtractionConfidence.HIGH),
                passengers = listOf(p("PRAKASH KUMAR", "12A"), p("BANDANA KUMARI", "12B")),
            )
        val json = Json.encodeToString(ExtractedFlight.serializer(), flight)
        val obj = Json.parseToJsonElement(json).jsonObject
        assertThat(
            obj
                .getValue("passengerName")
                .jsonObject
                .getValue("value")
                .jsonPrimitive.content,
        ).isEqualTo("PRAKASH KUMAR")
        assertThat(
            obj
                .getValue("seat")
                .jsonObject
                .getValue("value")
                .jsonPrimitive.content,
        ).isEqualTo("12A")
        assertThat(Json.decodeFromString(ExtractedFlight.serializer(), json)).isEqualTo(flight)
    }

    @Test
    fun `a pre-ADR-047 document without a passengers array decodes its flat fields as passenger one`() {
        val legacy =
            """{"passengerName": {"value": "RAHUL SHARMA", "confidence": "HIGH"},
                "seat": {"value": "12A", "confidence": "HIGH"},
                "sequenceNumber": {"value": "25", "confidence": "HIGH"}}"""
        val decoded = Json.decodeFromString(ExtractedFlight.serializer(), legacy)
        assertThat(decoded.passengers).containsExactly(
            ExtractedPassenger(
                name = ExtractedField.of("RAHUL SHARMA", ExtractionConfidence.HIGH),
                seat = ExtractedField.of("12A", ExtractionConfidence.HIGH),
                sequenceNumber = ExtractedField.of("25", ExtractionConfidence.HIGH),
            ),
        )
        assertThat(Json.decodeFromString(ExtractedFlight.serializer(), "{}").passengers).isEmpty()
    }
}
