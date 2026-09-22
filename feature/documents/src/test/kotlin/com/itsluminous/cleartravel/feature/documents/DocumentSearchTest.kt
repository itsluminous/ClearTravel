package com.itsluminous.cleartravel.feature.documents

import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.model.TravelDocument
import com.itsluminous.cleartravel.core.model.TravelDocumentType
import org.junit.Test

/** The pure name + type-label filter behind the Documents search box. */
class DocumentSearchTest {
    private val passport = document("Ada Lovelace passport", TravelDocumentType.PASSPORT)
    private val visa = document("Schengen", TravelDocumentType.VISA)
    private val insurance = document("Allianz policy", TravelDocumentType.INSURANCE)
    private val all = listOf(passport, visa, insurance)

    private val labels: (TravelDocumentType) -> String = { type ->
        when (type) {
            TravelDocumentType.PASSPORT -> "Passport"
            TravelDocumentType.VISA -> "Visa"
            TravelDocumentType.INSURANCE -> "Travel insurance"
            else -> "Other document"
        }
    }

    @Test
    fun `blank query keeps every document in order`() {
        assertThat(DocumentSearch.filter(all, "", labels)).isEqualTo(all)
        assertThat(DocumentSearch.filter(all, "   ", labels)).isEqualTo(all)
    }

    @Test
    fun `matches the name case-insensitively as a substring`() {
        assertThat(DocumentSearch.filter(all, "LOVELACE", labels)).containsExactly(passport)
        assertThat(DocumentSearch.filter(all, "schen", labels)).containsExactly(visa)
    }

    @Test
    fun `matches the type label even when the name does not mention it`() {
        // "Schengen" carries no "visa" — the type label does.
        assertThat(DocumentSearch.filter(all, "visa", labels)).containsExactly(visa)
        assertThat(DocumentSearch.filter(all, "insurance", labels)).containsExactly(insurance)
    }

    @Test
    fun `a term present in several documents keeps all of them in order`() {
        // "a" is in every name; the order of the input is preserved.
        assertThat(DocumentSearch.filter(all, "a", labels)).isEqualTo(all)
    }

    @Test
    fun `surrounding whitespace in the query is ignored`() {
        assertThat(DocumentSearch.filter(all, "  passport ", labels)).containsExactly(passport)
    }

    @Test
    fun `no match yields an empty list`() {
        assertThat(DocumentSearch.filter(all, "boarding", labels)).isEmpty()
    }

    @Test
    fun `matches never reads the note or file path`() {
        val noted = passport.copy(note = "kept in the blue folder", filePath = "/data/documents/secret.pdf")
        assertThat(DocumentSearch.matches(noted, "blue", "Passport")).isFalse()
        assertThat(DocumentSearch.matches(noted, "secret", "Passport")).isFalse()
    }

    private fun document(
        name: String,
        type: TravelDocumentType,
    ) = TravelDocument(name = name, type = type, filePath = "/data/documents/$name.png")
}
