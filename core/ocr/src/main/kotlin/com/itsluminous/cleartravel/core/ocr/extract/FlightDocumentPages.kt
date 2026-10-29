package com.itsluminous.cleartravel.core.ocr.extract

/**
 * Page-level triage of a multi-page flight document (ADR-045 pipeline: the OCR text of
 * every page joined by a blank line). Airline e-tickets append pages of conditions of
 * carriage, baggage rules and advisories — prose that quotes example flight numbers
 * (`6E-333`), terminals and city pairs and would otherwise read as extra flights.
 *
 * A page is PROSE when it reads like running text — at least [MIN_LINES] rows
 * averaging [MIN_AVG_WORDS_PER_LINE]+ words, with [MIN_LONG_LINE_WORD_SHARE] of its
 * words inside sentence-length rows — AND it carries no flight data of its own: no
 * parseable date and no labeled record locator. Itinerary pages average under seven
 * words a row (a boarding pass three); a one-page ticket with dense fine print keeps
 * its date and so is never dropped. Pure and deterministic; the extractors call it
 * before segmenting, so recorded fixtures exercise it.
 */
internal object FlightDocumentPages {
    /** The pipeline's page separator (and the ML Kit row text never contains a blank line). */
    const val PAGE_BREAK = "\n\n"

    /** [text] with its prose pages removed, pages re-joined by [PAGE_BREAK]. */
    fun withoutProsePages(text: String): String {
        val pages = text.split(PAGE_BREAK)
        if (pages.size <= 1) return text
        return pages.filterNot(::isProsePage).joinToString(PAGE_BREAK)
    }

    fun isProsePage(page: String): Boolean {
        val lines = page.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size < MIN_LINES) return false
        val wordCounts = lines.map { it.split(WHITESPACE).size }
        val words = wordCounts.sum()
        if (words.toDouble() / lines.size < MIN_AVG_WORDS_PER_LINE) return false
        val longWords = lines.indices.filter { wordCounts[it] >= LONG_LINE_WORDS }.sumOf { wordCounts[it] }
        if (longWords.toDouble() / words < MIN_LONG_LINE_WORD_SHARE) return false
        val upper = page.uppercase()
        val hasDate = lines.any { OcrDates.parseToIso(it) != null }
        val hasLocator = FlightSegmenter.LABELED_PNR_IN_LINE.containsMatchIn(upper)
        return !hasDate && !hasLocator
    }

    private val WHITESPACE = Regex("""\s+""")
    private const val MIN_LINES = 8
    private const val MIN_AVG_WORDS_PER_LINE = 11.0
    private const val LONG_LINE_WORDS = 12
    private const val MIN_LONG_LINE_WORD_SHARE = 0.5
}
