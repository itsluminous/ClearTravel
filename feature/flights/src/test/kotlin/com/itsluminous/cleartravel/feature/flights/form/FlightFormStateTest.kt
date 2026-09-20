package com.itsluminous.cleartravel.feature.flights.form

import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.ocr.ExtractionConfidence
import com.itsluminous.cleartravel.core.ocr.model.BoardingPassExtraction
import com.itsluminous.cleartravel.core.ocr.model.BoardingPassSource
import com.itsluminous.cleartravel.core.ocr.model.ExtractedField
import com.itsluminous.cleartravel.core.testing.Fixtures
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class FlightFormStateTest {
    private val valid =
        FlightFormState(
            airlineIata = "6E",
            flightNumber = "2345",
            dateText = "2026-09-25",
        )

    @Test
    fun `valid minimal form passes validation`() {
        assertThat(FlightFormState.validate(valid)).isEmpty()
    }

    @Test
    fun `airline must be a 2-character code`() {
        assertThat(FlightFormState.validate(valid.copy(airlineIata = "")))
            .contains(FlightFormError.AIRLINE_INVALID)
        assertThat(FlightFormState.validate(valid.copy(airlineIata = "ABC")))
            .contains(FlightFormError.AIRLINE_INVALID)
        // Lowercase input is normalized before matching.
        assertThat(FlightFormState.validate(valid.copy(airlineIata = "ai"))).isEmpty()
    }

    @Test
    fun `flight number must be 1-4 digits with optional suffix letter`() {
        assertThat(FlightFormState.validate(valid.copy(flightNumber = "")))
            .contains(FlightFormError.FLIGHT_NUMBER_INVALID)
        assertThat(FlightFormState.validate(valid.copy(flightNumber = "12345")))
            .contains(FlightFormError.FLIGHT_NUMBER_INVALID)
        assertThat(FlightFormState.validate(valid.copy(flightNumber = "101"))).isEmpty()
        assertThat(FlightFormState.validate(valid.copy(flightNumber = "482A"))).isEmpty()
    }

    @Test
    fun `date is mandatory and must be ISO`() {
        assertThat(FlightFormState.validate(valid.copy(dateText = "")))
            .contains(FlightFormError.DATE_INVALID)
        assertThat(FlightFormState.validate(valid.copy(dateText = "25/09/2026")))
            .contains(FlightFormError.DATE_INVALID)
    }

    @Test
    fun `times are optional but validated when present`() {
        assertThat(FlightFormState.validate(valid.copy(depTimeText = "27:00")))
            .contains(FlightFormError.DEP_TIME_INVALID)
        assertThat(FlightFormState.validate(valid.copy(arrTimeText = "later")))
            .contains(FlightFormError.ARR_TIME_INVALID)
        assertThat(FlightFormState.validate(valid.copy(depTimeText = "9:05", arrTimeText = "23:40"))).isEmpty()
    }

    @Test
    fun `toJourney normalizes fields and combines date with times`() {
        val zone = ZoneId.of("Asia/Kolkata")
        val journey =
            valid
                .copy(
                    airlineIata = "ai",
                    depAirport = "del",
                    arrAirport = "??",
                    depTimeText = "22:55",
                ).toJourney(checkInUrl = "https://example.test/checkin", zone = zone)

        assertThat(journey.airlineIata).isEqualTo("AI")
        assertThat(journey.depAirport).isEqualTo("DEL")
        assertThat(journey.arrAirport).isEmpty() // invalid airport code dropped
        assertThat(journey.schedDep)
            .isEqualTo(ZonedDateTime.of(2026, 9, 25, 22, 55, 0, 0, zone).toInstant())
        assertThat(journey.checkInUrl).isEqualTo("https://example.test/checkin")
    }

    @Test
    fun `fromJourney round-trips the editable fields`() {
        val source = Fixtures.flightJourney(seat = "12F", cabinClass = "Business")

        val state = FlightFormState.fromJourney(source)

        assertThat(state.editingId).isEqualTo(source.id)
        assertThat(state.airlineIata).isEqualTo(source.airlineIata)
        assertThat(state.seat).isEqualTo("12F")
        assertThat(state.dateText).isEqualTo(source.date.toString())
        assertThat(FlightFormState.validate(state)).isEmpty()
    }

    @Test
    fun `fromExtraction maps values and keeps per-field confidence markers`() {
        val extraction =
            BoardingPassExtraction(
                passengerName = ExtractedField.of("DOE/JOHN", ExtractionConfidence.HIGH),
                pnr = ExtractedField.of("AB1CD2", ExtractionConfidence.HIGH),
                carrier = ExtractedField.of("ai", ExtractionConfidence.HIGH),
                flightNumber = ExtractedField.of("0101", ExtractionConfidence.HIGH),
                fromAirport = ExtractedField.of("del", ExtractionConfidence.MEDIUM),
                toAirport = ExtractedField.of("fco", ExtractionConfidence.MEDIUM),
                flightDate = ExtractedField.of("2026-09-20", ExtractionConfidence.LOW),
                seat = ExtractedField.EMPTY,
                source = BoardingPassSource.BARCODE,
            )

        val state = FlightFormState.fromExtraction(extraction, passUri = "content://pass/1")

        assertThat(state.airlineIata).isEqualTo("AI")
        assertThat(state.flightNumber).isEqualTo("0101")
        assertThat(state.depAirport).isEqualTo("DEL")
        assertThat(state.prefillSource).isEqualTo(BoardingPassSource.BARCODE)
        assertThat(state.pendingPassUri).isEqualTo("content://pass/1")
        assertThat(state.confidences[FlightField.AIRLINE]).isEqualTo(ExtractionConfidence.HIGH)
        assertThat(state.confidences[FlightField.DATE]).isEqualTo(ExtractionConfidence.LOW)
        // NONE-confidence fields carry no marker.
        assertThat(state.confidences).doesNotContainKey(FlightField.SEAT)
    }
}
