package com.itsluminous.cleartravel.core.ocr.pipeline

/** One recognized text line with its bounding box in bitmap pixels. */
data class OcrLine(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val text: String,
) {
    val centerY: Float get() = (top + bottom) / 2f
    val height: Int get() = bottom - top
}

/**
 * Pure layout reconstruction for ML Kit output. `Text.text` concatenates blocks in
 * detection order, which for tabular documents (IRCTC ERS, boarding passes) scatters
 * a row's cells across the page — the label lands 40 lines away from its value and a
 * passenger's name, age and status never share a line. Rebuilding READING ORDER from
 * the line geometry fixes that: lines whose vertical centres fall within a fraction
 * of the typical line height are one visual row, ordered left-to-right and joined by
 * [CELL_SEPARATOR]; rows are emitted top-to-bottom. The two-space separator lets the
 * pure extractors split a row back into cells while `\s+`-tolerant regexes keep
 * working unchanged on single-column text.
 */
object OcrLayout {
    const val CELL_SEPARATOR = "  "

    fun toRowText(lines: List<OcrLine>): String {
        val usable = lines.filter { it.text.isNotBlank() && it.height > 0 }
        if (usable.isEmpty()) return ""
        val typicalHeight = usable.map { it.height }.sorted()[usable.size / 2].coerceAtLeast(1)
        val tolerance = typicalHeight * ROW_TOLERANCE

        val rows = mutableListOf<MutableList<OcrLine>>()
        for (line in usable.sortedBy { it.centerY }) {
            val current = rows.lastOrNull()
            if (current != null && kotlin.math.abs(rowCenter(current) - line.centerY) <= tolerance) {
                current += line
            } else {
                rows += mutableListOf(line)
            }
        }
        return rows.joinToString("\n") { row ->
            row.sortedBy { it.left }.joinToString(CELL_SEPARATOR) { it.text.trim() }
        }
    }

    private fun rowCenter(row: List<OcrLine>): Float = row.map { it.centerY }.average().toFloat()

    /** Fraction of the median line height two lines may differ by and still share a row. */
    private const val ROW_TOLERANCE = 0.6f
}
