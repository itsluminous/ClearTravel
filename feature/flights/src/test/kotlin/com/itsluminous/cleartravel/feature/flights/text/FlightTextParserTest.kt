package com.itsluminous.cleartravel.feature.flights.text

import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.ocr.ExtractionConfidence
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import java.time.LocalDate

/** Loads the shipped rules asset from the module directory (Gradle runs tests there). */
object AirlineSmsRulesFixture {
    fun assetFile(): File =
        listOf(
            File("src/main/assets/${AssetAirlineSmsRuleSource.ASSET_PATH}"),
            File("feature/flights/src/main/assets/${AssetAirlineSmsRuleSource.ASSET_PATH}"),
        ).firstOrNull(File::isFile)
            ?: error("${AssetAirlineSmsRuleSource.ASSET_PATH} asset not found from ${File(".").absolutePath}")

    val rules: AirlineSmsRules by lazy { AirlineSmsRulesParser.parse(assetFile().readText()) }

    fun parser(): FlightTextParser = FlightTextParser { rules }

    fun read(name: String): String =
        requireNotNull(javaClass.getResourceAsStream("/flight-sms/$name")) { "Missing fixture /flight-sms/$name" }
            .bufferedReader()
            .use { it.readText() }
}

/**
 * Fixture harness for the flight SMS/email parser (ADR-003/ADR-042): every recorded
 * message under `flight-sms/` is parsed with the SHIPPED rules asset and compared
 * against its expected-output JSON. A rule change that breaks a fixture fails here.
 */
@RunWith(Parameterized::class)
class FlightTextParserFixturesTest(
    private val fixture: String,
) {
    @Test
    fun parsesExpectedFields() {
        val text = AirlineSmsRulesFixture.read("$fixture.txt")
        val expected = Json.decodeFromString<FlightTextExtraction>(AirlineSmsRulesFixture.read("$fixture.expected.json"))

        assertThat(AirlineSmsRulesFixture.parser().parse(text, today = TODAY)).isEqualTo(expected)
    }

    companion object {
        /** Fixed clock so year-less dates resolve deterministically. */
        val TODAY: LocalDate = LocalDate.of(2026, 1, 15)

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun fixtures() = listOf("akasa-1", "indigo-1", "airindia-1", "vistara-1", "spicejet-1", "generic-1", "ota-email-1")
    }
}

/** Non-parameterized companion cases: the asset itself, fallbacks, format variants. */
class FlightTextParserBehaviorTest {
    private val parser = AirlineSmsRulesFixture.parser()
    private val rules = AirlineSmsRulesFixture.rules

    @Test
    fun `shipped asset parses, names every airline and every regex compiles`() {
        assertThat(rules.version).isAtLeast(1)
        assertThat(rules.airlines.keys).containsAtLeast("QP", "6E", "AI", "UK", "SG", "IX")
        for ((code, info) in rules.airlines) {
            assertThat(code).matches("[A-Z0-9]{2}")
            assertThat(info.name).isNotEmpty()
            assertThat(info.aliases).isNotEmpty()
            info.patterns.all().forEach { Regex(it) }
        }
        val generic = rules.patterns
        assertThat(generic.labeledFlight).isNotEmpty()
        assertThat(generic.labeledPnr).isNotEmpty()
        assertThat(generic.route).isNotEmpty()
        assertThat(generic.labeledDate).isNotEmpty()
        generic.all().forEach { Regex(it) }
        assertThat(rules.airlineName("qp")).isEqualTo("Akasa Air")
    }

    @Test
    fun `garbage fixture falls back to the empty result`() {
        val result = parser.parse(AirlineSmsRulesFixture.read("garbage.txt"))

        assertThat(result).isEqualTo(FlightTextExtraction.EMPTY)
        assertThat(result.isEmpty).isTrue()
    }

    @Test
    fun `an IRCTC train SMS is not read as a flight`() {
        assertThat(parser.parse(AirlineSmsRulesFixture.read("train-sms.txt"))).isEqualTo(FlightTextExtraction.EMPTY)
    }

    @Test
    fun `empty and hostile input never throw`() {
        assertThat(parser.parse("")).isEqualTo(FlightTextExtraction.EMPTY)
        assertThat(parser.parse("\u0000\uFFFD\n\t ~~~ ((( ")).isEqualTo(FlightTextExtraction.EMPTY)
    }

    @Test
    fun `unreadable rules degrade to the empty result`() {
        val broken = FlightTextParser { AirlineSmsRulesParser.parse("not json") }

        assertThat(broken.parse(AirlineSmsRulesFixture.read("akasa-1.txt"))).isEqualTo(FlightTextExtraction.EMPTY)
    }

    @Test
    fun `every requested date format resolves`() {
        val today = LocalDate.of(2026, 1, 15)
        val formats =
            mapOf(
                "29 May 26" to "2026-05-29",
                "29 May 2026" to "2026-05-29",
                "29-May-26" to "2026-05-29",
                "12/06/2026" to "2026-06-12",
                "2026-06-12" to "2026-06-12",
                "Jun 12, 2026" to "2026-06-12",
            )
        for ((raw, iso) in formats) {
            val result = parser.parse("Your flight AI 202 DEL-BOM on $raw, PNR ABC123", today)
            assertThat(result.flightDate.value).isEqualTo(iso)
            assertThat(result.flightDate.confidence).isEqualTo(ExtractionConfidence.HIGH)
        }
    }

    @Test
    fun `every requested route notation resolves`() {
        for (route in listOf("from BLR to VNS", "BLR-VNS", "BLR to VNS", "BLR → VNS", "BLR -> VNS")) {
            val result = parser.parse("Flight QP 1421 $route on 29 May 26")
            assertThat(result.fromAirport.value).isEqualTo("BLR")
            assertThat(result.toAirport.value).isEqualTo("VNS")
        }
    }

    @Test
    fun `times normalise to 24-hour HH mm and honour am pm`() {
        val result = parser.parse("Flight 6E 2001 DEL-BOM dep 6:35 pm, arr 8.45 pm on 12 Jun 26")

        assertThat(result.depTime.value).isEqualTo("18:35")
        assertThat(result.arrTime.value).isEqualTo("20:45")
    }

    @Test
    fun `an airline alias alone gives the carrier without a flight number`() {
        val result = parser.parse("Your Vistara booking is confirmed. Booking Reference: QWE789.")

        assertThat(result.carrier.value).isEqualTo("UK")
        assertThat(result.carrier.confidence).isEqualTo(ExtractionConfidence.MEDIUM)
        assertThat(result.airlineName.value).isEqualTo("Vistara")
        assertThat(result.flightNumber.isPresent).isFalse()
        assertThat(result.pnr.value).isEqualTo("QWE789")
    }

    @Test
    fun `the flight number's carrier beats a differing alias`() {
        val result = parser.parse("Air India Express flight IX 1234 from COK to DXB on 01-Jul-2026")

        assertThat(result.carrier.value).isEqualTo("IX")
        assertThat(result.airlineName.value).isEqualTo("Air India Express")
    }

    @Test
    fun `a glued flight number is never mistaken for a PNR`() {
        val result = parser.parse("Flight QP1421 BLR-VNS on 29 May 26")

        assertThat(result.flightNumber.value).isEqualTo("1421")
        assertThat(result.pnr.isPresent).isFalse()
    }

    @Test
    fun `a return leg is counted as an additional flight`() {
        val result = parser.parse("Onward 6E 2001 DEL-GOI on 10-01-2027. Return 6E 2002 GOI-DEL on 17-01-2027. PNR: A1B2C3")

        assertThat(result.flightNumber.value).isEqualTo("2001")
        assertThat(result.additionalFlights).isEqualTo(1)
    }

    @Test
    fun `a terminal after the arrival airport is the arrival terminal`() {
        val result = parser.parse("Flight SG 8195 from BLR (T1) to DEL (Terminal 3) on 12/06/2026")

        assertThat(result.depTerminal.value).isEqualTo("1")
        assertThat(result.arrTerminal.value).isEqualTo("3")
    }

    @Test
    fun `greeting stop-words are not passenger names`() {
        val result = parser.parse("Dear Customer, your flight 6E 2001 DEL-BOM on 12 Jun 26 is confirmed.")

        assertThat(result.passengerName.isPresent).isFalse()
    }
}
