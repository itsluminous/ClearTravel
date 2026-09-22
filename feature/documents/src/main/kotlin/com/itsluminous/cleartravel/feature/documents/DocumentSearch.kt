package com.itsluminous.cleartravel.feature.documents

import com.itsluminous.cleartravel.core.model.TravelDocument
import com.itsluminous.cleartravel.core.model.TravelDocumentType

/**
 * The Documents list's live search: a pure, case-insensitive substring match over a
 * document's NAME and its TYPE LABEL (the localized preset name, e.g. "Passport"),
 * so typing "pass" finds "Ada's passport" as well as an unrenamed passport, and
 * "visa" finds every visa whatever it was called. The UI resolves the labels — this
 * function never touches resources so it stays unit-testable.
 */
object DocumentSearch {
    /** A blank query matches everything (the list is unfiltered). */
    fun matches(
        document: TravelDocument,
        query: String,
        typeLabel: String,
    ): Boolean {
        val needle = query.trim()
        if (needle.isEmpty()) return true
        return document.name.contains(needle, ignoreCase = true) ||
            typeLabel.contains(needle, ignoreCase = true)
    }

    /** Keeps [documents] order; a blank [query] returns the input list unchanged. */
    fun filter(
        documents: List<TravelDocument>,
        query: String,
        typeLabel: (TravelDocumentType) -> String,
    ): List<TravelDocument> {
        if (query.isBlank()) return documents
        return documents.filter { matches(it, query, typeLabel(it.type)) }
    }
}
