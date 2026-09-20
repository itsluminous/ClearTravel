package com.itsluminous.cleartravel.feature.flights.form

import android.content.Context
import android.net.Uri
import com.itsluminous.cleartravel.core.data.repository.AttachmentRepository
import com.itsluminous.cleartravel.core.model.Attachment
import com.itsluminous.cleartravel.core.model.AttachmentOwnerType
import com.itsluminous.cleartravel.core.model.EntityIds
import com.itsluminous.cleartravel.core.ocr.OcrPrefillService
import com.itsluminous.cleartravel.core.ocr.model.BookingConfirmationExtraction
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Feature-level seam over `core:ocr` + attachment storage for booking-confirmation
 * imports, mirroring [BoardingPassImporter] so ViewModels stay plain-JVM testable
 * (tests substitute a fake; URIs travel as strings).
 *
 * Unlike boarding passes (a path column on the flight row — frozen schema, gate
 * display stays special), booking confirmations are stored as [Attachment] rows
 * (`ownerType` FLIGHT + mimeType), which puts them on the Drive upload queue and in
 * backup bundles automatically (ADR-016/ADR-015; ADR-017).
 */
interface BookingConfirmationImporter {
    /** Runs the barcode-first / OCR-fallback booking-confirmation pipeline. */
    suspend fun prefill(uriString: String): BookingConfirmationExtraction

    /**
     * Copies the picked file into app-private storage and persists an [Attachment]
     * row owned by [flightId]. Returns the saved row, or null when the copy failed
     * (the flight saves without the document).
     */
    suspend fun attach(
        uriString: String,
        flightId: String,
    ): Attachment?
}

/** Production importer: `OcrPrefillService` + a copy into `filesDir/attachments/`. */
@Singleton
class OcrBookingConfirmationImporter
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val ocrPrefillService: OcrPrefillService,
        private val attachmentRepository: AttachmentRepository,
    ) : BookingConfirmationImporter {
        override suspend fun prefill(uriString: String): BookingConfirmationExtraction =
            runCatching { ocrPrefillService.prefillBookingConfirmation(Uri.parse(uriString)) }
                .getOrDefault(BookingConfirmationExtraction.EMPTY)

        override suspend fun attach(
            uriString: String,
            flightId: String,
        ): Attachment? =
            withContext(Dispatchers.IO) {
                runCatching {
                    val uri = Uri.parse(uriString)
                    val attachmentId = EntityIds.newId()
                    val mime = mimeOf(uri)
                    // Same directory the backup/Drive restore ladder re-points into
                    // (`filesDir/attachments/<id>` — ADR-015/ADR-016).
                    val dir = File(context.filesDir, ATTACHMENTS_DIR).apply { mkdirs() }
                    val target = File(dir, "$attachmentId.${extensionOf(mime, uri)}")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    } ?: return@runCatching null
                    attachmentRepository.save(
                        Attachment(
                            id = attachmentId,
                            ownerType = AttachmentOwnerType.FLIGHT,
                            ownerId = flightId,
                            localPath = target.absolutePath,
                            mimeType = mime,
                        ),
                    )
                }.getOrNull()
            }

        private fun mimeOf(uri: Uri): String =
            context.contentResolver.getType(uri)
                ?: if (uri.toString().endsWith(".pdf", ignoreCase = true)) "application/pdf" else "image/jpeg"

        private fun extensionOf(
            mime: String,
            uri: Uri,
        ): String =
            when {
                mime == "application/pdf" || uri.toString().endsWith(".pdf", ignoreCase = true) -> "pdf"
                mime == "image/png" -> "png"
                else -> "jpg"
            }

        private companion object {
            const val ATTACHMENTS_DIR = "attachments"
        }
    }
