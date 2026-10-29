package com.itsluminous.cleartravel.feature.itinerary.place

import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.feature.itinerary.fakes.VirtualPacer
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** ADR-048: Nominatim's 1 req/s policy, enforced app-wide. */
class GeocodePacerTest {
    @Test
    fun `the first call runs at once, later ones wait out the remaining interval`() =
        runTest {
            val virtual = VirtualPacer()
            val order = mutableListOf<Int>()

            virtual.pacer.paced { order += 1 }
            virtual.now += 400
            virtual.pacer.paced { order += 2 }
            virtual.now += 1_500
            virtual.pacer.paced { order += 3 }

            assertThat(order).containsExactly(1, 2, 3).inOrder()
            assertThat(virtual.waits).containsExactly(600L)
        }

    @Test
    fun `a failing block still stamps the interval`() =
        runTest {
            val virtual = VirtualPacer()

            runCatching { virtual.pacer.paced<Unit> { error("boom") } }
            virtual.pacer.paced { }

            assertThat(virtual.waits).containsExactly(GeocodePacer.MIN_INTERVAL_MILLIS)
        }
}
