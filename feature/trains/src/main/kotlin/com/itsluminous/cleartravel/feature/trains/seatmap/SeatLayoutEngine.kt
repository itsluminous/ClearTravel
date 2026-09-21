package com.itsluminous.cleartravel.feature.trains.seatmap

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** One numbered berth/seat placed in the expanded coach grid. */
data class Berth(
    val number: Int,
    val type: BerthType,
    /** 1-based bay (or seat row for seating classes). */
    val bay: Int,
    val row: Int,
    val column: Int,
    val block: SeatBlock,
)

/** One visual row of a bay: the cells left of the aisle and the cells right of it. */
data class BayRow(
    val left: List<Berth>,
    val right: List<Berth>,
)

data class Bay(
    /** 1-based. */
    val index: Int,
    val rows: List<BayRow>,
) {
    val berths: List<Berth> get() = rows.flatMap { it.left + it.right }
}

/** The fully expanded seat map of one coach class. */
data class SeatLayout(
    val classCode: String,
    val displayName: String,
    val kind: SeatKind,
    val bays: List<Bay>,
) {
    private val byNumber: Map<Int, Berth> by lazy { bays.flatMap(Bay::berths).associateBy(Berth::number) }

    val total: Int get() = byNumber.size

    /** Placement of berth [number]; null when outside `1..total`. */
    fun berth(number: Int): Berth? = byNumber[number]
}

/** Typed failure for an unusable layout file (malformed JSON or an invalid template). */
sealed class SeatLayoutException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    class MalformedJson(
        cause: Throwable,
    ) : SeatLayoutException("Seat layout JSON is malformed: ${cause.message}", cause)

    class InvalidTemplate(
        message: String,
    ) : SeatLayoutException(message)
}

/**
 * PURE expansion of a [SeatLayoutDefinition] into the full coach grid (ADR-022):
 * berth `n` → template offset `(n - 1) % perBay` in bay `(n - 1) / perBay + 1`,
 * rows inside a bay ordered by `row`, cells inside a row ordered by `column`. Never
 * silently produces a wrong map: a template whose offsets are not exactly
 * `0 until perBay`, a non-positive total, or negative positions throws a typed
 * [SeatLayoutException.InvalidTemplate]; unparseable JSON throws
 * [SeatLayoutException.MalformedJson].
 */
object SeatLayoutEngine {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): SeatLayoutDefinition =
        try {
            json.decodeFromString(SeatLayoutDefinition.serializer(), text)
        } catch (e: SerializationException) {
            throw SeatLayoutException.MalformedJson(e)
        } catch (e: IllegalArgumentException) {
            throw SeatLayoutException.MalformedJson(e)
        }

    fun expand(definition: SeatLayoutDefinition): SeatLayout {
        validate(definition)
        val byOffset = definition.template.associateBy(SeatCell::offset)
        val bayCount = (definition.total + definition.perBay - 1) / definition.perBay
        val bays =
            (1..bayCount).map { bayIndex ->
                val first = (bayIndex - 1) * definition.perBay + 1
                val last = minOf(bayIndex * definition.perBay, definition.total)
                val berths =
                    (first..last).map { number ->
                        val cell = byOffset.getValue((number - 1) % definition.perBay)
                        Berth(
                            number = number,
                            type = cell.type,
                            bay = bayIndex,
                            row = cell.row,
                            column = cell.column,
                            block = cell.block,
                        )
                    }
                val rows =
                    berths
                        .groupBy(Berth::row)
                        .toSortedMap()
                        .values
                        .map { rowBerths ->
                            BayRow(
                                left = rowBerths.filter { it.block == SeatBlock.LEFT }.sortedBy(Berth::column),
                                right = rowBerths.filter { it.block == SeatBlock.RIGHT }.sortedBy(Berth::column),
                            )
                        }
                Bay(index = bayIndex, rows = rows)
            }
        return SeatLayout(
            classCode = definition.classCode,
            displayName = definition.displayName,
            kind = definition.kind,
            bays = bays,
        )
    }

    /** `parse` + `expand` in one call. */
    fun load(text: String): SeatLayout = expand(parse(text))

    private fun validate(definition: SeatLayoutDefinition) {
        fun fail(message: String): Nothing = throw SeatLayoutException.InvalidTemplate("${definition.classCode}: $message")
        if (definition.classCode.isBlank()) fail("classCode is blank")
        if (definition.total < 1) fail("total must be >= 1, was ${definition.total}")
        if (definition.perBay < 1) fail("perBay must be >= 1, was ${definition.perBay}")
        val offsets = definition.template.map(SeatCell::offset).sorted()
        if (offsets != (0 until definition.perBay).toList()) {
            fail("template offsets must be exactly 0..${definition.perBay - 1}, were $offsets")
        }
        if (definition.template.any { it.row < 0 || it.column < 0 }) fail("row/column must be >= 0")
    }
}
