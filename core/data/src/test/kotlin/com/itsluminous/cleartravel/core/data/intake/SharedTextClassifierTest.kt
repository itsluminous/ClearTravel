package com.itsluminous.cleartravel.core.data.intake

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** ADR-042: the train-vs-flight preselection for shared `text/plain`. */
class SharedTextClassifierTest {
    @Test
    fun `the Akasa sample is a flight`() {
        val scores = SharedTextClassifier.score(AKASA)

        assertThat(scores.suggested).isEqualTo(SharedTextKind.FLIGHT)
        assertThat(scores.flight).isGreaterThan(scores.train)
        assertThat(scores.train).isEqualTo(0)
    }

    @Test
    fun `the IRCTC SMS fixture is a train`() {
        val scores = SharedTextClassifier.score(IRCTC_SMS)

        assertThat(scores.suggested).isEqualTo(SharedTextKind.TRAIN)
        assertThat(scores.train).isGreaterThan(scores.flight)
    }

    @Test
    fun `every flight-style message is a flight`() {
        val messages =
            listOf(
                "IndiGo: Dear Rahul, your booking is confirmed. PNR/Booking Ref: ABC123. Flight 6E-2001 from Delhi (DEL) to Goa (GOI) on 12 Jun 26, departs 06:35.",
                "Air India: Your booking AI 202 DEL-BOM on 12-Jun-2026 is confirmed. PNR: ABC123.",
                "Vistara | Booking Reference: QWE789 | Flight UK 955 | New Delhi (DEL) → Mumbai (BOM) | Jun 12, 2026",
                "SpiceJet: Hi Neha, your flight SG 8195 (BLR to DEL) is scheduled for 12/06/2026 at 21:40. PNR no. is ZX9K2L.",
                "Your flight AI 202 DEL-BOM on 12-Jun-2026, PNR ABC123",
                "Boarding pass for QP 1421, gate 12, terminal 1",
            )
        for (message in messages) {
            assertThat(SharedTextClassifier.classify(message)).isEqualTo(SharedTextKind.FLIGHT)
        }
    }

    @Test
    fun `every train-style message is a train`() {
        val messages =
            listOf(
                "PNR:1234567890,TRN:12627,DOJ:20-10-26,SL,SBC-NDLS,Dep:20:00",
                "Your train 12951 Rajdhani Express departs NDLS at 16:25. PNR 8524167890. Coach B4 berth 32.",
                "IRCTC booking confirmed for 20-09-25, 3A, RAC 12",
                "Check out my train ticket (PNR 8553674906): https://cleartravel.itsluminous.com/pnr/8553674906",
            )
        for (message in messages) {
            assertThat(SharedTextClassifier.classify(message)).isEqualTo(SharedTextKind.TRAIN)
        }
    }

    @Test
    fun `no evidence or a tie defaults to train for backward compatibility`() {
        assertThat(SharedTextClassifier.score("hello there")).isEqualTo(SharedTextScores(train = 0, flight = 0))
        assertThat(SharedTextClassifier.classify("hello there")).isEqualTo(SharedTextKind.TRAIN)
        assertThat(SharedTextClassifier.classify(null)).isEqualTo(SharedTextKind.TRAIN)
        assertThat(SharedTextClassifier.classify("   ")).isEqualTo(SharedTextKind.TRAIN)
        assertThat(SharedTextScores(train = 2, flight = 2).suggested).isEqualTo(SharedTextKind.TRAIN)
    }

    @Test
    fun `ambiguous text follows the stronger evidence`() {
        // Mentions a train but is a flight message: flight number + IATA pair + airline outweigh one word.
        assertThat(SharedTextClassifier.classify("Take the train to the airport for IndiGo 6E 2001 DEL-BOM"))
            .isEqualTo(SharedTextKind.FLIGHT)
        // Mentions a flight but carries an IRCTC PNR + train number + class.
        assertThat(SharedTextClassifier.classify("Missed my flight, booked PNR 8524167890 TRN 12951 3A instead"))
            .isEqualTo(SharedTextKind.TRAIN)
    }

    @Test
    fun `common two-letter words followed by numbers are not flight numbers`() {
        assertThat(SharedTextClassifier.score("Meeting ON 2026 AT 1030 IN 4th floor").flight).isEqualTo(0)
    }

    private companion object {
        const val AKASA =
            "Dear Bandana, you have opted to auto select your seat for Akasa Air flight QP 1421 with PNR X4F18V from BLR " +
                "(Terminal 1) to VNS on 29 May 26. Your boarding pass with the assigned seat will be sent to you six hours " +
                "before flight departure. We look forward to welcoming you on board and enjoy the Akasa experience."
        const val IRCTC_SMS =
            "PNR:8524167890,TRN:12951,DOJ:20-09-25,3A,NDLS-BCT,DP:16:25,RAHUL SHARMA+1,B4 32,B4 33,CNF,Fare:4830.00,SC:11.8+PG CHGS"
    }
}
