package com.itsluminous.cleartravel.ui.security

import com.itsluminous.cleartravel.core.designsystem.component.DocumentFileReader
import com.itsluminous.cleartravel.core.security.file.LocalFileCipher
import java.io.File
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The app shell's [DocumentFileReader] (ADR-031): decrypts CTEF files through the
 * vault-keyed [LocalFileCipher] (legacy plaintext files pass through unchanged, so a
 * not-yet-migrated file still renders). Installed once via
 * `LocalDocumentFileReader` so every feature's viewer decrypts transparently while
 * `core:designsystem` stays free of any security dependency.
 */
@Singleton
class EncryptedDocumentFileReader
    @Inject
    constructor(
        private val fileCipher: LocalFileCipher,
    ) : DocumentFileReader {
        override fun open(path: String): InputStream? {
            val file = File(path).takeIf(File::isFile) ?: return null
            return runCatching { fileCipher.openDecrypted(file) }.getOrNull()
        }

        override fun materialize(
            path: String,
            cacheDir: File,
        ): File? {
            val source = File(path).takeIf(File::isFile) ?: return null
            val target = File(cacheDir.apply { mkdirs() }, source.name)
            return runCatching {
                target.outputStream().buffered().use { out -> fileCipher.decryptTo(source, out) }
                target
            }.onFailure { target.delete() }.getOrNull()
        }
    }
