package com.itsluminous.cleartravel.feature.trains.seatmap

import kotlinx.serialization.Serializable

/**
 * Wire schema of one `assets/seat-layouts/<classCode>.json` file (ADR-022, behavior
 * as data — ADR-003): a coach class's seat/berth map is a BAY TEMPLATE repeated
 * [total] / [perBay] times. Berth number `n` (1-based) lives in bay
 * `(n - 1) / perBay + 1` at template offset `(n - 1) % perBay`; the last bay may be
 * partial (a 46-berth 2A tail bay holds offsets 0..3 only). Each template cell fixes
 * the berth's type label and where it renders inside the bay: [SeatCell.row] (a bay
 * of Indian Railways berths draws as two facing rows), [SeatCell.column] within its
 * [SeatCell.block] — the LEFT block sits before the aisle, the RIGHT block (side
 * berths / the second seat group) after it.
 *
 * [SeatLayoutEngine] validates and expands a definition; every asset file is pinned
 * by the parameterized `SeatLayoutAssetTest`.
 */
@Serializable
data class SeatLayoutDefinition(
    val version: Int,
    /** Stable class key — also the file name (`SL`, `3A`, `2A`, `1A`, `CC`, `EC`, `2S`, `GN`). */
    val classCode: String,
    /** Coach-class display name (data, like airline names in the check-in table). */
    val displayName: String,
    val kind: SeatKind = SeatKind.BERTH,
    /** Total berths/seats in the coach. */
    val total: Int,
    /** Template length — berths per bay (or seats per row for seating classes). */
    val perBay: Int,
    /** Maintainer note; never rendered. */
    val note: String = "",
    val template: List<SeatCell>,
)

@Serializable
enum class SeatKind { BERTH, SEAT }

/** Where one template offset renders and what it is. */
@Serializable
data class SeatCell(
    /** 0-based position inside the bay: berth `n` uses offset `(n - 1) % perBay`. */
    val offset: Int,
    val type: BerthType,
    /** 0-based row inside the bay (berth bays have two facing rows). */
    val row: Int = 0,
    /** 0-based column inside the block. */
    val column: Int = 0,
    val block: SeatBlock = SeatBlock.LEFT,
)

/** Berth/seat type — rendered through string resources by the UI, never raw. */
@Serializable
enum class BerthType {
    LOWER,
    MIDDLE,
    UPPER,
    SIDE_LOWER,
    SIDE_UPPER,
    WINDOW,
    AISLE,
}

/** Which side of the aisle a cell renders on. */
@Serializable
enum class SeatBlock { LEFT, RIGHT }
