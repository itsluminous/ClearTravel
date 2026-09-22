package com.itsluminous.cleartravel.core.designsystem.component

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * File-level helpers behind the shared viewer's Share / Save-a-copy actions and its
 * page loader (ADR-030). The pure functions ([DocumentFiles.mimeTypeFor],
 * [DocumentFiles.suggestedFileName], [DocumentFiles.sampleSizeFor],
 * [DocumentFiles.pdfRenderSize]) are unit-tested; the Android entry points are thin.
 */
object DocumentFiles {
    /** MIME type from the file extension; the viewer decides PDF-vs-image the same way. */
    fun mimeTypeFor(path: String): String =
        when (path.substringAfterLast('.', "").lowercase()) {
            "pdf" -> MIME_PDF
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "bmp" -> "image/bmp"
            "heic", "heif" -> "image/heic"
            else -> MIME_BINARY
        }

    fun isPdf(path: String): Boolean = mimeTypeFor(path) == MIME_PDF

    /**
     * Name proposed to the system file picker: the user-facing [title] with characters
     * that are unsafe on FAT/SAF stripped, falling back to [fallback], plus the
     * source's extension (when it has one).
     */
    fun suggestedFileName(
        title: String,
        sourcePath: String,
        fallback: String = "document",
    ): String {
        val cleaned =
            title
                .replace(UNSAFE_CHARS, "_")
                .trim()
                .trim('.', '_', ' ')
                .take(MAX_NAME_LENGTH)
                .ifBlank { fallback }
        val extension = sourcePath.substringAfterLast('/').substringAfterLast('.', "").lowercase()
        return if (extension.isBlank() || cleaned.endsWith(".$extension", ignoreCase = true)) cleaned else "$cleaned.$extension"
    }

    /**
     * Power-of-two `inSampleSize` so a [width]×[height] image decodes with its longer
     * side at most [maxDimension] px — keeps a 48 MP camera scan under ~25 MB in RAM.
     */
    fun sampleSizeFor(
        width: Int,
        height: Int,
        maxDimension: Int = MAX_IMAGE_DIMENSION_PX,
    ): Int {
        var sample = 1
        val longest = max(width, height)
        if (longest <= 0) return 1
        while (longest / (sample * 2) >= maxDimension) sample *= 2
        return sample
    }

    /**
     * Bitmap size for a PDF page of [pageWidth]×[pageHeight] points rendered at
     * [PDF_RENDER_WIDTH_PX] wide (≈2× a 1080p display so 2–3× zoom stays crisp) but
     * never taller than [MAX_PDF_HEIGHT_PX] (very long pages scale down instead).
     */
    fun pdfRenderSize(
        pageWidth: Int,
        pageHeight: Int,
    ): Pair<Int, Int> {
        if (pageWidth <= 0 || pageHeight <= 0) return 1 to 1
        val scale = min(PDF_RENDER_WIDTH_PX.toFloat() / pageWidth, MAX_PDF_HEIGHT_PX.toFloat() / pageHeight)
        return max(1, (pageWidth * scale).toInt()) to max(1, (pageHeight * scale).toInt())
    }

    const val MIME_PDF = "application/pdf"
    const val MIME_BINARY = "application/octet-stream"
    const val PDF_RENDER_WIDTH_PX = 2160
    const val MAX_PDF_HEIGHT_PX = 4096
    const val MAX_IMAGE_DIMENSION_PX = 2560
    private const val MAX_NAME_LENGTH = 80
    private val UNSAFE_CHARS = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")
}

/** One rendered page plus the document's page count (1 for images). */
class DocumentPage(
    val bitmap: Bitmap,
    val pageCount: Int,
)

/**
 * Decodes [pageIndex] of the document at [path]: images decode down-sampled to
 * [DocumentFiles.MAX_IMAGE_DIMENSION_PX]; PDF pages render white-backed at
 * [DocumentFiles.pdfRenderSize]. `null` when the file is missing or unreadable.
 * Blocking — call on an IO dispatcher.
 */
fun loadDocumentPage(
    path: String,
    pageIndex: Int,
): DocumentPage? =
    runCatching {
        val file = File(path)
        if (!file.exists()) return null
        if (DocumentFiles.isPdf(path)) {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    if (renderer.pageCount == 0) return null
                    val index = pageIndex.coerceIn(0, renderer.pageCount - 1)
                    renderer.openPage(index).use { page ->
                        val (width, height) = DocumentFiles.pdfRenderSize(page.width, page.height)
                        val target = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        target.eraseColor(android.graphics.Color.WHITE)
                        page.render(target, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        DocumentPage(target, renderer.pageCount)
                    }
                }
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            val options =
                BitmapFactory.Options().apply {
                    inSampleSize = DocumentFiles.sampleSizeFor(bounds.outWidth, bounds.outHeight)
                }
            BitmapFactory.decodeFile(path, options)?.let { DocumentPage(it, 1) }
        }
    }.getOrNull()

/** Backwards-compatible first-page loader (ADR-027 callers). */
fun loadDocumentBitmap(path: String): Bitmap? = loadDocumentPage(path, 0)?.bitmap

/**
 * `ACTION_SEND` of the stored file through the app's `FileProvider` (authority
 * `<applicationId>.fileprovider`, roots in the app's `file_paths.xml`). Returns `false`
 * when the file is missing, outside a provider root, or no app can receive it.
 */
fun shareDocumentFile(
    context: Context,
    path: String,
    chooserTitle: String,
): Boolean =
    runCatching {
        val file = File(path)
        if (!file.exists()) return false
        val uri = FileProvider.getUriForFile(context, "${context.packageName}$FILE_PROVIDER_SUFFIX", file)
        val send =
            Intent(Intent.ACTION_SEND).apply {
                type = DocumentFiles.mimeTypeFor(path)
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        val chooser = Intent.createChooser(send, chooserTitle).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        context.startActivity(chooser)
        true
    }.getOrDefault(false)

/**
 * Copies the stored file's bytes to a SAF [target] the user picked via
 * `CreateDocument`. Returns `false` on any I/O failure. Blocking — IO dispatcher.
 */
fun copyDocumentTo(
    context: Context,
    path: String,
    target: Uri,
): Boolean =
    runCatching {
        val file = File(path)
        if (!file.exists()) return false
        val out = context.contentResolver.openOutputStream(target, "wt") ?: return false
        out.use { sink -> file.inputStream().use { source -> source.copyTo(sink) } }
        true
    }.getOrDefault(false)

private const val FILE_PROVIDER_SUFFIX = ".fileprovider"
