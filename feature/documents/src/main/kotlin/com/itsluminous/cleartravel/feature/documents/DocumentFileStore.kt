package com.itsluminous.cleartravel.feature.documents

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import com.itsluminous.cleartravel.core.data.repository.TravelDocumentStorage
import com.itsluminous.cleartravel.core.security.file.LocalFileCipher
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** A document file copied into app-private storage. */
data class StoredDocumentFile(
    val path: String,
    val mimeType: String,
)

/**
 * Feature seam over the content resolver + `filesDir/documents/` so
 * [DocumentsViewModel] stays plain-JVM testable (tests substitute a fake; URIs travel
 * as strings). ADR-027.
 */
interface DocumentFileStore {
    /**
     * Copies the picked [uriString] to `filesDir/documents/<documentId>.<ext>` and
     * returns the stored path + resolved MIME type — null when the copy failed.
     */
    suspend fun store(
        uriString: String,
        documentId: String,
    ): StoredDocumentFile?

    /** Removes a previously stored file; missing files are not an error. */
    suspend fun delete(path: String)
}

/**
 * Production store: `ContentResolver` copy into [TravelDocumentStorage.directory],
 * written ENCRYPTED through the vault-keyed [LocalFileCipher] (ADR-031).
 */
@Singleton
class LocalDocumentFileStore
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val fileCipher: LocalFileCipher,
    ) : DocumentFileStore {
        override suspend fun store(
            uriString: String,
            documentId: String,
        ): StoredDocumentFile? =
            withContext(Dispatchers.IO) {
                runCatching {
                    val uri = Uri.parse(uriString)
                    val mimeType = context.contentResolver.getType(uri).orEmpty()
                    val dir = TravelDocumentStorage.directory(context.filesDir).apply { mkdirs() }
                    val target = File(dir, TravelDocumentStorage.fileName(documentId, extensionFor(uri, mimeType)))
                    val input = context.contentResolver.openInputStream(uri) ?: return@runCatching null
                    try {
                        // encryptTo writes to a temp file and swaps, so a failure never
                        // leaves a half-written file behind an unsaved row.
                        input.use { source -> fileCipher.encryptTo(source, target) }
                    } catch (e: Exception) {
                        target.delete()
                        throw e
                    }
                    StoredDocumentFile(path = target.absolutePath, mimeType = mimeType)
                }.getOrNull()
            }

        override suspend fun delete(path: String) {
            withContext(Dispatchers.IO) { runCatching { File(path).delete() } }
        }

        private fun extensionFor(
            uri: Uri,
            mimeType: String,
        ): String =
            DocumentFileNames.extensionFor(uri.lastPathSegment.orEmpty(), mimeType, MimeTypeMap.getSingleton()::getExtensionFromMimeType)
    }

/** Pure extension resolution — the viewer keys PDF rendering off the stored extension. */
object DocumentFileNames {
    fun extensionFor(
        uriName: String,
        mimeType: String,
        fromMime: (String) -> String?,
    ): String =
        when {
            mimeType == "application/pdf" || uriName.endsWith(".pdf", ignoreCase = true) -> "pdf"
            mimeType.isNotBlank() -> fromMime(mimeType) ?: "jpg"
            else -> uriName.substringAfterLast('.', "").takeIf { it.isNotBlank() && it.length <= 5 } ?: "jpg"
        }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class DocumentsModule {
    @Binds
    @Singleton
    abstract fun bindDocumentFileStore(impl: LocalDocumentFileStore): DocumentFileStore
}
