package com.itsluminous.cleartravel.core.designsystem.component

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DocumentFilesTest {
    @Test
    fun `mime type follows the extension, case-insensitively`() {
        assertThat(DocumentFiles.mimeTypeFor("/data/documents/x.PDF")).isEqualTo("application/pdf")
        assertThat(DocumentFiles.mimeTypeFor("/data/documents/x.png")).isEqualTo("image/png")
        assertThat(DocumentFiles.mimeTypeFor("scan.JPEG")).isEqualTo("image/jpeg")
        assertThat(DocumentFiles.mimeTypeFor("scan.jpg")).isEqualTo("image/jpeg")
        assertThat(DocumentFiles.mimeTypeFor("scan.webp")).isEqualTo("image/webp")
        assertThat(DocumentFiles.mimeTypeFor("noext")).isEqualTo(DocumentFiles.MIME_BINARY)
        assertThat(DocumentFiles.mimeTypeFor("weird.xyz")).isEqualTo(DocumentFiles.MIME_BINARY)
        assertThat(DocumentFiles.isPdf("a.pdf")).isTrue()
        assertThat(DocumentFiles.isPdf("a.png")).isFalse()
    }

    @Test
    fun `suggested file name keeps the title and appends the source extension`() {
        assertThat(DocumentFiles.suggestedFileName("Ada passport", "/files/documents/uuid.pdf")).isEqualTo("Ada passport.pdf")
        assertThat(DocumentFiles.suggestedFileName("Boarding pass", "/files/boarding_passes/uuid.PNG")).isEqualTo("Boarding pass.png")
    }

    @Test
    fun `suggested file name strips unsafe characters and never doubles the extension`() {
        assertThat(DocumentFiles.suggestedFileName("Trip: DEL/BOM <2026>?", "x.pdf")).isEqualTo("Trip_ DEL_BOM _2026.pdf")
        assertThat(DocumentFiles.suggestedFileName("ticket.pdf", "/a/b.pdf")).isEqualTo("ticket.pdf")
        assertThat(DocumentFiles.suggestedFileName("   ", "/a/b.jpg")).isEqualTo("document.jpg")
        assertThat(DocumentFiles.suggestedFileName("...", "/a/b.jpg", fallback = "pass")).isEqualTo("pass.jpg")
        assertThat(DocumentFiles.suggestedFileName("Plain", "/a/noext")).isEqualTo("Plain")
    }

    @Test
    fun `suggested file name is truncated to a sane length`() {
        val long = "x".repeat(200)
        assertThat(DocumentFiles.suggestedFileName(long, "a.pdf")).isEqualTo("x".repeat(80) + ".pdf")
    }

    @Test
    fun `sample size is the largest power of two keeping the longer side within the cap`() {
        assertThat(DocumentFiles.sampleSizeFor(1000, 800)).isEqualTo(1)
        assertThat(DocumentFiles.sampleSizeFor(2560, 1920)).isEqualTo(1)
        assertThat(DocumentFiles.sampleSizeFor(4000, 3000)).isEqualTo(1) // 4000/2 = 2000 < 2560 → stays 1
        assertThat(DocumentFiles.sampleSizeFor(6000, 4000)).isEqualTo(2) // 3000 ≥ 2560 → 2; 1500 < 2560 stop
        assertThat(DocumentFiles.sampleSizeFor(12000, 9000)).isEqualTo(4)
        assertThat(DocumentFiles.sampleSizeFor(0, 0)).isEqualTo(1)
        assertThat(DocumentFiles.sampleSizeFor(800, 800, maxDimension = 100)).isEqualTo(8)
    }

    @Test
    fun `pdf render size targets 2160 wide and caps very tall pages`() {
        // A4 portrait: 595×842 pt → width-limited.
        val (w, h) = DocumentFiles.pdfRenderSize(595, 842)
        assertThat(w).isEqualTo(2160)
        assertThat(h).isEqualTo((842 * (2160f / 595)).toInt())
        assertThat(h).isAtMost(DocumentFiles.MAX_PDF_HEIGHT_PX)

        // Receipt-style strip 200×4000 pt → height-limited at 4096.
        val (sw, sh) = DocumentFiles.pdfRenderSize(200, 4000)
        assertThat(sh).isEqualTo(4096)
        assertThat(sw).isEqualTo((200 * (4096f / 4000)).toInt())

        assertThat(DocumentFiles.pdfRenderSize(0, 10)).isEqualTo(1 to 1)
    }
}
