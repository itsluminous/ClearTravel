package com.itsluminous.cleartravel.core.data.sync

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** ADR-044: the PNR never appears in the plaintext store — only its SHA-256. */
class PnrHashTest {
    @Test
    fun hash_isSha256Hex_normalisedOnWhitespaceAndCase() {
        val hash = PnrHash.of("8524167890")
        assertThat(hash).hasLength(64)
        assertThat(hash).matches("[0-9a-f]{64}")
        assertThat(hash).doesNotContain("8524167890")
        assertThat(PnrHash.of(" 8524167890 ")).isEqualTo(hash)
        assertThat(PnrHash.of("1234509876")).isNotEqualTo(hash)
    }

    @Test
    fun notificationId_isStable_nonNegative_andDiffersPerPnr() {
        val a = PnrHash.notificationId("8524167890")
        assertThat(a).isEqualTo(PnrHash.notificationId("8524167890"))
        assertThat(a).isAtLeast(0)
        assertThat(a).isNotEqualTo(PnrHash.notificationId("1234509876"))
    }
}
