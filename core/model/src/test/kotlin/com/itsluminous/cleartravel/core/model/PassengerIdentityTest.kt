package com.itsluminous.cleartravel.core.model

import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.model.PassengerIdentity.Keys
import org.junit.Test

/** ADR-047: the ONE passenger-identity rule shared by core:data and core:ocr. */
class PassengerIdentityTest {
    private val prakash = Keys(name = "PRAKASH KUMAR", seat = "12A")
    private val bandana = Keys(name = "BANDANA KUMARI", seat = "12B")

    @Test
    fun `normalizeName treats BCBP, ticket and OTA spellings as one person`() {
        val bcbp = PassengerIdentity.normalizeName("SHARMA/RAHUL")
        assertThat(bcbp).isEqualTo(PassengerIdentity.normalizeName("Rahul Sharma"))
        assertThat(bcbp).isEqualTo(PassengerIdentity.normalizeName("MR RAHUL SHARMA"))
        assertThat(bcbp).isEqualTo(PassengerIdentity.normalizeName("Sharma, Rahul"))
        assertThat(bcbp).isNotEqualTo(PassengerIdentity.normalizeName("Rahul Kumar"))
        assertThat(PassengerIdentity.normalizeName(" / ")).isEmpty()
    }

    @Test
    fun `normalizeSeat sequence and pnr drop case and leading zeros`() {
        assertThat(PassengerIdentity.normalizeSeat("010a ")).isEqualTo("10A")
        assertThat(PassengerIdentity.normalizeSeat("")).isEmpty()
        assertThat(PassengerIdentity.normalizeSequence("0025")).isEqualTo("25")
        assertThat(PassengerIdentity.normalizePnr(" x8b2q4 ")).isEqualTo("X8B2Q4")
    }

    @Test
    fun `same name matches regardless of seat and position`() {
        assertThat(PassengerIdentity.indexOfMatch(listOf(prakash, bandana), Keys(name = "Kumari/Bandana", seat = "14C"))).isEqualTo(1)
    }

    @Test
    fun `a different name on the same seat is a new person (name wins)`() {
        assertThat(PassengerIdentity.indexOfMatch(listOf(prakash), Keys(name = "RAHUL SHARMA", seat = "12A"))).isEqualTo(-1)
    }

    @Test
    fun `a named record claims a nameless one on the same seat only`() {
        val nameless = Keys(seat = "12A")
        assertThat(PassengerIdentity.indexOfMatch(listOf(bandana, nameless), Keys(name = "PRAKASH KUMAR", seat = "12A"))).isEqualTo(1)
        assertThat(PassengerIdentity.indexOfMatch(listOf(bandana, nameless), Keys(name = "PRAKASH KUMAR", seat = "14C"))).isEqualTo(-1)
        assertThat(PassengerIdentity.indexOfMatch(listOf(bandana, nameless), Keys(name = "PRAKASH KUMAR"))).isEqualTo(-1)
    }

    @Test
    fun `without names the seat decides, then sequence, then pnr`() {
        val bySeq = Keys(sequenceNumber = "0042")
        val byPnr = Keys(pnr = "abc123")
        assertThat(PassengerIdentity.indexOfMatch(listOf(prakash, bandana), Keys(seat = "12b"))).isEqualTo(1)
        assertThat(PassengerIdentity.indexOfMatch(listOf(prakash), Keys(seat = "9F"))).isEqualTo(-1)
        assertThat(PassengerIdentity.indexOfMatch(listOf(prakash, bySeq), Keys(sequenceNumber = "42"))).isEqualTo(1)
        assertThat(PassengerIdentity.indexOfMatch(listOf(prakash, byPnr), Keys(pnr = "ABC123"))).isEqualTo(1)
        assertThat(PassengerIdentity.indexOfMatch(listOf(prakash), Keys())).isEqualTo(-1)
    }
}
