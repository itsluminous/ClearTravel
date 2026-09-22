package com.itsluminous.cleartravel.feature.documents

import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.model.TravelDocumentType
import org.junit.Test
import java.time.LocalDate

class DocumentExpiryTest {
    private val today = LocalDate.of(2026, 9, 22)

    @Test
    fun `no expiry is NONE`() {
        assertThat(DocumentExpiry.stateOf(null, today)).isEqualTo(ExpiryState.NONE)
    }

    @Test
    fun `yesterday is EXPIRED, today is still EXPIRING_SOON`() {
        assertThat(DocumentExpiry.stateOf(today.minusDays(1), today)).isEqualTo(ExpiryState.EXPIRED)
        assertThat(DocumentExpiry.stateOf(today, today)).isEqualTo(ExpiryState.EXPIRING_SOON)
    }

    @Test
    fun `six-month window - inside is EXPIRING_SOON, just outside is VALID`() {
        assertThat(DocumentExpiry.stateOf(today.plusDays(DocumentExpiry.SOON_DAYS), today)).isEqualTo(ExpiryState.EXPIRING_SOON)
        assertThat(DocumentExpiry.stateOf(today.plusDays(DocumentExpiry.SOON_DAYS + 1), today)).isEqualTo(ExpiryState.VALID)
        assertThat(DocumentExpiry.stateOf(today.plusYears(9), today)).isEqualTo(ExpiryState.VALID)
    }

    @Test
    fun `format renders a medium locale date containing day month and year`() {
        val formatted = DocumentExpiry.format(LocalDate.of(2031, 3, 12))
        assertThat(formatted).contains("2031")
        assertThat(formatted).contains("12")
        assertThat(formatted).doesNotContain("2031-03-12")
    }

    @Test
    fun `every type has a preset label and icon and OTHER is last`() {
        assertThat(DocumentTypePresets.ordered).containsExactlyElementsIn(TravelDocumentType.entries)
        assertThat(DocumentTypePresets.ordered.last()).isEqualTo(TravelDocumentType.OTHER)
        val labels = TravelDocumentType.entries.map(DocumentTypePresets::labelRes)
        assertThat(labels.toSet()).hasSize(TravelDocumentType.entries.size)
        val icons = TravelDocumentType.entries.map(DocumentTypePresets::icon)
        assertThat(icons.toSet()).hasSize(TravelDocumentType.entries.size)
    }

    @Test
    fun `identity papers usually expire, tickets and other do not`() {
        assertThat(DocumentTypePresets.usuallyExpires(TravelDocumentType.PASSPORT)).isTrue()
        assertThat(DocumentTypePresets.usuallyExpires(TravelDocumentType.INSURANCE)).isTrue()
        assertThat(DocumentTypePresets.usuallyExpires(TravelDocumentType.TICKET)).isFalse()
        assertThat(DocumentTypePresets.usuallyExpires(TravelDocumentType.OTHER)).isFalse()
    }

    @Test
    fun `file extension - pdf wins by mime or name, images resolve from mime, unknown falls back to jpg`() {
        val fromMime = { mime: String -> mapOf("image/png" to "png", "image/webp" to "webp")[mime] }
        assertThat(DocumentFileNames.extensionFor("scan", "application/pdf", fromMime)).isEqualTo("pdf")
        assertThat(DocumentFileNames.extensionFor("Passport.PDF", "", fromMime)).isEqualTo("pdf")
        assertThat(DocumentFileNames.extensionFor("photo", "image/png", fromMime)).isEqualTo("png")
        assertThat(DocumentFileNames.extensionFor("photo", "image/unknown", fromMime)).isEqualTo("jpg")
        assertThat(DocumentFileNames.extensionFor("IMG_0001.heic", "", fromMime)).isEqualTo("heic")
        assertThat(DocumentFileNames.extensionFor("document:1234", "", fromMime)).isEqualTo("jpg")
    }
}
