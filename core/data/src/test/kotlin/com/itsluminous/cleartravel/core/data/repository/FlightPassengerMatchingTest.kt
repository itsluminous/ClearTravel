package com.itsluminous.cleartravel.core.data.repository

import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.testing.Fixtures
import org.junit.Test

/** ADR-046 decision 4: who is already on the flight. */
class FlightPassengerMatchingTest {
    private val prakash = Fixtures.flightPassenger(name = "PRAKASH KUMAR", seat = "12A", sortOrder = 0)
    private val bandanaReturn = Fixtures.flightPassenger(name = "BANDANA KUMARI", seat = "10A", sortOrder = 1)

    @Test
    fun `hasPassengerInfo needs a name or a seat`() {
        assertThat(FlightPassengerMatching.hasPassengerInfo(Fixtures.flightPassenger(name = "", seat = ""))).isFalse()
        assertThat(FlightPassengerMatching.hasPassengerInfo(Fixtures.flightPassenger(name = "", seat = "", pnrBookingRef = "X"))).isFalse()
        assertThat(FlightPassengerMatching.hasPassengerInfo(Fixtures.flightPassenger(name = "", seat = "12A"))).isTrue()
        assertThat(FlightPassengerMatching.hasPassengerInfo(Fixtures.flightPassenger(name = "A", seat = ""))).isTrue()
    }

    @Test
    fun `normalizeName treats BCBP, ticket and OTA spellings as one person`() {
        val bcbp = FlightPassengerMatching.normalizeName("SHARMA/RAHUL")
        assertThat(bcbp).isEqualTo(FlightPassengerMatching.normalizeName("Rahul Sharma"))
        assertThat(bcbp).isEqualTo(FlightPassengerMatching.normalizeName("MR RAHUL SHARMA"))
        assertThat(bcbp).isEqualTo(FlightPassengerMatching.normalizeName("Sharma, Rahul"))
        assertThat(bcbp).isNotEqualTo(FlightPassengerMatching.normalizeName("Rahul Kumar"))
        assertThat(FlightPassengerMatching.normalizeName(" / ")).isEmpty()
    }

    @Test
    fun `normalizeSeat drops case and leading zeros`() {
        assertThat(FlightPassengerMatching.normalizeSeat("010a ")).isEqualTo("10A")
        assertThat(FlightPassengerMatching.normalizeSeat("")).isEmpty()
    }

    @Test
    fun `same name matches regardless of seat`() {
        val incoming = Fixtures.flightPassenger(name = "Kumar/Prakash", seat = "14C")
        assertThat(FlightPassengerMatching.findMatch(listOf(prakash, bandanaReturn), incoming)).isEqualTo(prakash)
    }

    @Test
    fun `a different name on the same seat is a new passenger (name wins)`() {
        // The real e-ticket: Bandana in 10A on the return leg; the image had Prakash in 10A.
        val existing = listOf(Fixtures.flightPassenger(name = "PRAKASH KUMAR", seat = "10A"))
        val incoming = Fixtures.flightPassenger(name = "BANDANA KUMARI", seat = "10A")
        assertThat(FlightPassengerMatching.findMatch(existing, incoming)).isNull()
    }

    @Test
    fun `a named passenger claims a nameless row with the same seat (legacy backfill)`() {
        val legacy = Fixtures.flightPassenger(name = "", seat = "12A")
        val incoming = Fixtures.flightPassenger(name = "PRAKASH KUMAR", seat = "12A")
        assertThat(FlightPassengerMatching.findMatch(listOf(legacy), incoming)).isEqualTo(legacy)
        assertThat(FlightPassengerMatching.findMatch(listOf(legacy), incoming.copy(seat = "12B"))).isNull()
    }

    @Test
    fun `without names the seat decides, then sequence, then passenger PNR`() {
        val seated = Fixtures.flightPassenger(name = "", seat = "12A")
        assertThat(FlightPassengerMatching.findMatch(listOf(prakash), Fixtures.flightPassenger(name = "", seat = "12a"))).isEqualTo(prakash)
        assertThat(FlightPassengerMatching.findMatch(listOf(seated), Fixtures.flightPassenger(name = "", seat = "12B"))).isNull()

        val sequenced = Fixtures.flightPassenger(name = "", seat = "", sequenceNumber = "0042")
        assertThat(
            FlightPassengerMatching.findMatch(listOf(sequenced), Fixtures.flightPassenger(name = "", seat = "", sequenceNumber = "42")),
        ).isEqualTo(sequenced)

        val byPnr = Fixtures.flightPassenger(name = "", seat = "", pnrBookingRef = "R87T5G")
        assertThat(
            FlightPassengerMatching.findMatch(listOf(byPnr), Fixtures.flightPassenger(name = "", seat = "", pnrBookingRef = "r87t5g")),
        ).isEqualTo(byPnr)
        assertThat(FlightPassengerMatching.findMatch(listOf(byPnr), Fixtures.flightPassenger(name = "", seat = ""))).isNull()
    }

    @Test
    fun `fillBlanks completes an existing row without touching its pass`() {
        val legacy = Fixtures.flightPassenger(name = "", seat = "12A", boardingPassPath = "/p/legacy.pdf")
        val incoming =
            Fixtures.flightPassenger(
                name = "PRAKASH KUMAR",
                seat = "14A",
                pnrBookingRef = "R87T5G",
                boardingPassPath = "/p/new.pdf",
            )
        val filled = FlightPassengerMatching.fillBlanks(legacy, incoming)
        assertThat(filled.name).isEqualTo("PRAKASH KUMAR")
        assertThat(filled.seat).isEqualTo("12A")
        assertThat(filled.pnrBookingRef).isEqualTo("R87T5G")
        assertThat(filled.boardingPassPath).isEqualTo("/p/legacy.pdf")
        assertThat(filled.id).isEqualTo(legacy.id)
    }

    @Test
    fun `mirror takes passenger 1's seat and the first pass found`() {
        val flight = Fixtures.flightJourney(seat = "old", boardingPassPath = "/old")
        val mirrored =
            FlightPassengerMatching.mirror(
                flight,
                listOf(
                    Fixtures.flightPassenger(seat = " 12A ", boardingPassPath = null, sortOrder = 0),
                    Fixtures.flightPassenger(seat = "12B", boardingPassPath = "/p/b.pdf", sortOrder = 1),
                ),
            )
        assertThat(mirrored.seat).isEqualTo("12A")
        assertThat(mirrored.boardingPassPath).isEqualTo("/p/b.pdf")
        val none = FlightPassengerMatching.mirror(flight, emptyList())
        assertThat(none.seat).isEmpty()
        assertThat(none.boardingPassPath).isNull()
    }
}
