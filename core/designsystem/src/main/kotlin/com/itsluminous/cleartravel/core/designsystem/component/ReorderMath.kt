package com.itsluminous.cleartravel.core.designsystem.component

/**
 * Pure index math behind drag-to-reorder: returns a copy of this list with the element
 * at [from] removed and re-inserted at [to] (the other elements keep their relative
 * order). Out-of-range indices or `from == to` return the list unchanged.
 */
fun <T> List<T>.moved(
    from: Int,
    to: Int,
): List<T> {
    if (from == to || from !in indices || to !in indices) return this
    return toMutableList().apply { add(to, removeAt(from)) }
}
