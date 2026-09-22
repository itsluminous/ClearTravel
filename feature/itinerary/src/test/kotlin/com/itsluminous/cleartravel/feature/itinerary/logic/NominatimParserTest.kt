package com.itsluminous.cleartravel.feature.itinerary.logic

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NominatimParserTest {
    @Test
    fun `parses multiple hits into labeled places`() {
        val body =
            """
            [
              {"display_name":"Taj Mahal, Agra, India","lat":"27.1751","lon":"78.0421"},
              {"display_name":"Taj Mahal Palace, Mumbai, India","lat":"18.9217","lon":"72.8332"},
              {"display_name":"Taj Mahal Restaurant, Berlin, Germany","lat":"52.5200","lon":"13.4050"}
            ]
            """.trimIndent()

        val places = parseNominatimResponse(body)

        assertThat(places).hasSize(3)
        assertThat(places[0].label).isEqualTo("Taj Mahal, Agra, India")
        assertThat(places[0].point.latitude).isWithin(1e-6).of(27.1751)
        assertThat(places[1].point.longitude).isWithin(1e-6).of(72.8332)
    }

    @Test
    fun `skips hits with unparseable coordinates or missing names`() {
        val body =
            """
            [
              {"display_name":"Good, Place","lat":"1.5","lon":"2.5"},
              {"display_name":"Bad Coords","lat":"abc","lon":"2.5"},
              {"display_name":"","lat":"1.0","lon":"2.0"}
            ]
            """.trimIndent()

        assertThat(parseNominatimResponse(body)).hasSize(1)
    }

    @Test
    fun `garbage or empty body parses to an empty list`() {
        assertThat(parseNominatimResponse("not json at all")).isEmpty()
        assertThat(parseNominatimResponse("")).isEmpty()
        assertThat(parseNominatimResponse("{}")).isEmpty()
    }

    @Test
    fun `unknown extra fields are ignored`() {
        val body = """[{"display_name":"X, Y","lat":"1.0","lon":"2.0","importance":0.8,"place_id":42}]"""
        assertThat(parseNominatimResponse(body)).hasSize(1)
    }
}
