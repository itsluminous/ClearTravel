package com.itsluminous.cleartravel.feature.trains.seatmap

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * THE fixture test for the `assets/seat-layouts/` files (ADR-003/ADR-022): enumerates
 * EVERY layout file, requires that it parses + expands, that its file name matches
 * its `classCode`, that berth numbers are exactly `1..total` with no gaps, and that
 * a hand-checked SAMPLE placement from [EXPECTED] holds. A new layout file without
 * an entry in [EXPECTED] fails by construction — a layout is never shipped
 * unpinned.
 */
@RunWith(Parameterized::class)
class SeatLayoutAssetTest(
    private val classCode: String,
) {
    /** One pinned berth per class: number → (type, bay). */
    data class Sample(
        val number: Int,
        val type: BerthType,
        val bay: Int,
    )

    private fun layout(): SeatLayout {
        val text =
            requireNotNull(FileSeatLayoutSource().open(classCode)) { "Missing layout $classCode" }
                .use { it.readBytes().decodeToString() }
        return SeatLayoutEngine.load(text)
    }

    @Test
    fun `file name matches classCode and the expanded map is dense 1 to total`() {
        val layout = layout()

        assertThat(layout.classCode).isEqualTo(classCode)
        assertThat(layout.displayName).isNotEmpty()
        val numbers =
            layout.bays
                .flatMap(Bay::berths)
                .map(Berth::number)
                .sorted()
        assertThat(numbers).isEqualTo((1..layout.total).toList())
        assertThat(layout.bays.map(Bay::index)).isEqualTo((1..layout.bays.size).toList())
    }

    @Test
    fun `every layout has a pinned sample and the sample placement holds`() {
        val expected =
            assertWithMessage("Layout '$classCode' has no pinned sample in SeatLayoutAssetTest.EXPECTED — add one")
                .that(EXPECTED[classCode])
        expected.isNotNull()
        val layout = layout()

        for (sample in EXPECTED.getValue(classCode).samples) {
            val berth = requireNotNull(layout.berth(sample.number)) { "$classCode has no berth ${sample.number}" }
            assertWithMessage("$classCode berth ${sample.number}").that(berth.type).isEqualTo(sample.type)
            assertWithMessage("$classCode berth ${sample.number} bay").that(berth.bay).isEqualTo(sample.bay)
        }
        assertThat(layout.total).isEqualTo(EXPECTED.getValue(classCode).total)
        assertThat(layout.berth(layout.total + 1)).isNull()
        assertThat(layout.berth(0)).isNull()
    }

    data class Pin(
        val total: Int,
        val samples: List<Sample>,
    )

    companion object {
        val EXPECTED: Map<String, Pin> =
            mapOf(
                "SL" to
                    Pin(
                        72,
                        listOf(
                            Sample(1, BerthType.LOWER, 1),
                            Sample(2, BerthType.MIDDLE, 1),
                            Sample(3, BerthType.UPPER, 1),
                            Sample(7, BerthType.SIDE_LOWER, 1),
                            Sample(8, BerthType.SIDE_UPPER, 1),
                            Sample(23, BerthType.SIDE_LOWER, 3),
                            Sample(72, BerthType.SIDE_UPPER, 9),
                        ),
                    ),
                "3A" to
                    Pin(
                        64,
                        listOf(
                            Sample(1, BerthType.LOWER, 1),
                            Sample(64, BerthType.SIDE_UPPER, 8),
                            Sample(33, BerthType.LOWER, 5),
                        ),
                    ),
                "2A" to
                    Pin(
                        46,
                        listOf(
                            Sample(1, BerthType.LOWER, 1),
                            Sample(5, BerthType.SIDE_LOWER, 1),
                            Sample(6, BerthType.SIDE_UPPER, 1),
                            Sample(43, BerthType.LOWER, 8),
                            Sample(46, BerthType.UPPER, 8),
                        ),
                    ),
                "1A" to
                    Pin(
                        24,
                        listOf(
                            Sample(1, BerthType.LOWER, 1),
                            Sample(4, BerthType.UPPER, 1),
                            Sample(24, BerthType.UPPER, 6),
                        ),
                    ),
                "CC" to
                    Pin(
                        78,
                        listOf(
                            Sample(1, BerthType.WINDOW, 1),
                            Sample(3, BerthType.AISLE, 1),
                            Sample(5, BerthType.WINDOW, 1),
                            Sample(78, BerthType.AISLE, 16),
                        ),
                    ),
                "EC" to
                    Pin(
                        56,
                        listOf(
                            Sample(1, BerthType.WINDOW, 1),
                            Sample(2, BerthType.AISLE, 1),
                            Sample(56, BerthType.WINDOW, 14),
                        ),
                    ),
                "2S" to
                    Pin(
                        108,
                        listOf(
                            Sample(1, BerthType.WINDOW, 1),
                            Sample(2, BerthType.MIDDLE, 1),
                            Sample(4, BerthType.AISLE, 1),
                            Sample(108, BerthType.WINDOW, 18),
                        ),
                    ),
                "GN" to
                    Pin(
                        90,
                        listOf(
                            Sample(1, BerthType.WINDOW, 1),
                            Sample(90, BerthType.WINDOW, 15),
                        ),
                    ),
            )

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun classCodes(): List<String> {
            val codes = FileSeatLayoutSource().classCodes()
            check(codes.isNotEmpty()) { "No seat layout files found" }
            return codes
        }
    }
}
