package com.itsluminous.cleartravel.feature.itinerary.logic

/**
 * Stable per-day accent palette (markers, polylines, day headers). Indexing is a
 * pure modulo of `dayIndex`, so a day keeps its color as other days change.
 */
private val DAY_COLORS_ARGB =
    listOf(
        0xFF0B57D0, // blue (seed)
        0xFFB3261E, // red
        0xFF146C2E, // green
        0xFF7D5260, // mauve
        0xFF984716, // orange
        0xFF006874, // teal
        0xFF6750A4, // purple
        0xFF5B6300, // olive
    )

/** ARGB color for [dayIndex]; cycles the palette and tolerates negative input. */
fun dayColorArgb(dayIndex: Int): Long {
    val size = DAY_COLORS_ARGB.size
    return DAY_COLORS_ARGB[((dayIndex % size) + size) % size]
}
