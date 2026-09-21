package com.itsluminous.cleartravel.feature.trains.seatmap

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class SeatLayoutEngineTest {
    private val sleeperTemplate =
        listOf(
            SeatCell(0, BerthType.LOWER, row = 0, column = 0),
            SeatCell(1, BerthType.MIDDLE, row = 0, column = 1),
            SeatCell(2, BerthType.UPPER, row = 0, column = 2),
            SeatCell(3, BerthType.LOWER, row = 1, column = 0),
            SeatCell(4, BerthType.MIDDLE, row = 1, column = 1),
            SeatCell(5, BerthType.UPPER, row = 1, column = 2),
            SeatCell(6, BerthType.SIDE_LOWER, row = 0, column = 0, block = SeatBlock.RIGHT),
            SeatCell(7, BerthType.SIDE_UPPER, row = 1, column = 0, block = SeatBlock.RIGHT),
        )

    private fun definition(
        total: Int = 16,
        perBay: Int = 8,
        template: List<SeatCell> = sleeperTemplate,
        classCode: String = "SL",
    ) = SeatLayoutDefinition(
        version = 1,
        classCode = classCode,
        displayName = "Sleeper",
        total = total,
        perBay = perBay,
        template = template,
    )

    @Test
    fun `expands bays with two facing rows and side berths on the right of the aisle`() {
        val layout = SeatLayoutEngine.expand(definition())

        assertThat(layout.bays).hasSize(2)
        val bay1 = layout.bays[0]
        assertThat(bay1.rows).hasSize(2)
        assertThat(bay1.rows[0].left.map(Berth::number)).containsExactly(1, 2, 3).inOrder()
        assertThat(bay1.rows[0].right.map(Berth::number)).containsExactly(7)
        assertThat(bay1.rows[1].left.map(Berth::number)).containsExactly(4, 5, 6).inOrder()
        assertThat(bay1.rows[1].right.map(Berth::number)).containsExactly(8)
        assertThat(
            layout.bays[1]
                .rows[0]
                .right
                .single()
                .type,
        ).isEqualTo(BerthType.SIDE_LOWER)
        assertThat(layout.berth(15)?.bay).isEqualTo(2)
    }

    @Test
    fun `a partial tail bay only holds the remaining berths`() {
        val layout = SeatLayoutEngine.expand(definition(total = 12))

        assertThat(layout.bays).hasSize(2)
        assertThat(layout.bays[1].berths.map(Berth::number)).containsExactly(9, 10, 11, 12).inOrder()
        assertThat(layout.bays[1].rows[0].right).isEmpty()
        assertThat(layout.total).isEqualTo(12)
        assertThat(layout.berth(13)).isNull()
    }

    @Test
    fun `template offsets that are not a dense 0 to perBay-1 range are a typed error`() {
        val broken = sleeperTemplate.map { if (it.offset == 7) it.copy(offset = 9) else it }

        val error =
            assertThrows(SeatLayoutException.InvalidTemplate::class.java) {
                SeatLayoutEngine.expand(definition(template = broken))
            }
        assertThat(error.message).contains("offsets")
    }

    @Test
    fun `non-positive total or perBay is a typed error`() {
        assertThrows(SeatLayoutException.InvalidTemplate::class.java) { SeatLayoutEngine.expand(definition(total = 0)) }
        assertThrows(SeatLayoutException.InvalidTemplate::class.java) {
            SeatLayoutEngine.expand(definition(perBay = 0, template = emptyList()))
        }
    }

    @Test
    fun `malformed json is a typed error`() {
        assertThrows(SeatLayoutException.MalformedJson::class.java) { SeatLayoutEngine.parse("{ this is not json") }
        assertThrows(SeatLayoutException.MalformedJson::class.java) { SeatLayoutEngine.parse("""{"version": 1}""") }
        assertThrows(SeatLayoutException.MalformedJson::class.java) {
            SeatLayoutEngine.parse(
                """{"version":1,"classCode":"X","displayName":"X","total":1,"perBay":1,"template":[{"offset":0,"type":"SOFA"}]}""",
            )
        }
    }

    @Test
    fun `catalog returns null for unknown classes and caches parsed layouts`() {
        val catalog = SeatLayoutCatalog(FileSeatLayoutSource())

        assertThat(catalog.layoutFor("XX")).isNull()
        assertThat(catalog.layoutFor("")).isNull()
        val first = catalog.layoutFor("sl")
        assertThat(first?.classCode).isEqualTo("SL")
        assertThat(catalog.layoutFor("SL")).isSameInstanceAs(first)
    }
}
