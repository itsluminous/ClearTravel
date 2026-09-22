package com.itsluminous.cleartravel.core.designsystem.component

import androidx.compose.runtime.staticCompositionLocalOf
import java.io.File
import java.io.InputStream

/**
 * How the shared viewer reads a stored document's bytes (ADR-031). The design system
 * must not know about encryption, so it only defines this seam: [Plain] streams the
 * file as-is, and the app shell installs a decrypting implementation through
 * [LocalDocumentFileReader] once at the top of the composition — feature modules
 * keep passing plain paths to [DocumentViewerScreen] and never touch keys.
 */
interface DocumentFileReader {
    /** Plaintext stream of the file at [path]; null when it is missing or unreadable. */
    fun open(path: String): InputStream?

    /**
     * A plaintext COPY of the file in [cacheDir], for consumers that need a seekable
     * file (`PdfRenderer`) or a shareable `FileProvider` URI. The caller owns and
     * deletes it. Null when the source is missing or unreadable.
     */
    fun materialize(
        path: String,
        cacheDir: File,
    ): File?

    /** Unencrypted files (the default; also what the hermetic e2e suite sees). */
    object Plain : DocumentFileReader {
        override fun open(path: String): InputStream? = File(path).takeIf(File::isFile)?.inputStream()

        override fun materialize(
            path: String,
            cacheDir: File,
        ): File? {
            val source = File(path).takeIf(File::isFile) ?: return null
            val target = File(cacheDir.apply { mkdirs() }, source.name)
            source.copyTo(target, overwrite = true)
            return target
        }
    }
}

/** The reader every [DocumentViewerScreen] uses; the app provides the decrypting one. */
val LocalDocumentFileReader = staticCompositionLocalOf<DocumentFileReader> { DocumentFileReader.Plain }
