package com.itsluminous.cleartravel.core.security.file

import com.itsluminous.cleartravel.core.security.crypto.ChunkedAead
import com.itsluminous.cleartravel.core.security.crypto.CryptoPrimitives
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.nio.ByteBuffer
import javax.crypto.SecretKey

/**
 * On-device file encryption (ADR-031, format **CTEF v1**) for documents, boarding
 * passes and attachments, keyed by the vault's file sub-key:
 *
 * ```
 * "CTEF" (4) | version=1 (1) | chunkSize int32 BE (4) | nonce (8) | ChunkedAead body
 * ```
 *
 * The 17-byte header is the AAD of every chunk. Reads are **format-tolerant**: a
 * file without the magic is streamed as plaintext — required by the one-time
 * migration (which must read the old bytes to encrypt them) and harmless for
 * confidentiality (the app only ever WRITES encrypted files). [isEncrypted] is the
 * migration's "already done" check.
 */
class LocalFileCipher(
    private val key: () -> SecretKey,
    private val chunkSize: Int = ChunkedAead.DEFAULT_CHUNK_SIZE,
) {
    /** Wraps [out] so everything written to the result lands encrypted; close it to seal. */
    fun encryptingStream(out: OutputStream): OutputStream {
        val nonce = CryptoPrimitives.randomBytes(ChunkedAead.NONCE_BYTES)
        val header = header(chunkSize, nonce)
        out.write(header)
        return ChunkedAead.encryptingStream(out, key(), nonce, header, chunkSize)
    }

    /** Plaintext view of [input] — decrypting when the CTEF header is present, pass-through otherwise. */
    fun decryptingStream(input: InputStream): InputStream {
        val pushback = PushbackInputStream(input, HEADER_BYTES)
        val header = ByteArray(HEADER_BYTES)
        val read = readFully(pushback, header)
        if (read < HEADER_BYTES || !hasMagic(header)) {
            if (read > 0) pushback.unread(header, 0, read)
            return pushback
        }
        if (header[MAGIC.size] != VERSION) throw IOException("unsupported CTEF version ${header[MAGIC.size]}")
        val buffer = ByteBuffer.wrap(header, MAGIC.size + 1, Int.SIZE_BYTES)
        val storedChunkSize = buffer.int
        if (storedChunkSize <= 0 || storedChunkSize > MAX_CHUNK_SIZE) throw IOException("bad CTEF chunk size")
        val nonce = header.copyOfRange(HEADER_BYTES - ChunkedAead.NONCE_BYTES, HEADER_BYTES)
        return ChunkedAead.decryptingStream(pushback, key(), nonce, header, storedChunkSize)
    }

    fun openDecrypted(file: File): InputStream = decryptingStream(file.inputStream().buffered())

    /** Whether [file] already carries the CTEF header (i.e. needs no migration). */
    fun isEncrypted(file: File): Boolean {
        if (!file.isFile || file.length() < HEADER_BYTES) return false
        val header = ByteArray(MAGIC.size)
        file.inputStream().use { if (readFully(it, header) < MAGIC.size) return false }
        return hasMagic(header)
    }

    /** Writes [source] to [target] encrypted (target replaced atomically via a temp file). */
    fun encryptTo(
        source: InputStream,
        target: File,
    ) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "${target.name}.enc-tmp")
        try {
            encryptingStream(temp.outputStream().buffered()).use { out -> source.copyTo(out) }
            replace(temp, target)
        } catch (e: Exception) {
            temp.delete()
            throw e
        }
    }

    /** Copies the plaintext of [source] into [out] (decrypting or passing through). */
    fun decryptTo(
        source: File,
        out: OutputStream,
    ) {
        openDecrypted(source).use { it.copyTo(out) }
    }

    /**
     * One-time migration step: rewrites a plaintext [file] in place as CTEF. A file
     * that is already encrypted is left untouched. Returns true when it encrypted.
     */
    fun encryptInPlace(file: File): Boolean {
        if (!file.isFile || isEncrypted(file)) return false
        file.inputStream().buffered().use { source -> encryptTo(source, file) }
        return true
    }

    private fun replace(
        temp: File,
        target: File,
    ) {
        if (target.exists() && !target.delete()) throw IOException("cannot replace ${target.name}")
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
    }

    companion object {
        val MAGIC: ByteArray = "CTEF".encodeToByteArray()
        const val VERSION: Byte = 1
        const val HEADER_BYTES = 4 + 1 + Int.SIZE_BYTES + ChunkedAead.NONCE_BYTES
        private const val MAX_CHUNK_SIZE = 16 * 1024 * 1024

        internal fun header(
            chunkSize: Int,
            nonce: ByteArray,
        ): ByteArray =
            ByteBuffer
                .allocate(HEADER_BYTES)
                .put(MAGIC)
                .put(VERSION)
                .putInt(chunkSize)
                .put(nonce)
                .array()

        internal fun hasMagic(bytes: ByteArray): Boolean = bytes.size >= MAGIC.size && MAGIC.indices.all { bytes[it] == MAGIC[it] }

        internal fun readFully(
            input: InputStream,
            target: ByteArray,
        ): Int {
            var filled = 0
            while (filled < target.size) {
                val n = input.read(target, filled, target.size - filled)
                if (n < 0) break
                filled += n
            }
            return filled
        }
    }
}
