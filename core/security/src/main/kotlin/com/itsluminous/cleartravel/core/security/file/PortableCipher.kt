package com.itsluminous.cleartravel.core.security.file

import com.itsluminous.cleartravel.core.security.crypto.ChunkedAead
import com.itsluminous.cleartravel.core.security.crypto.CryptoPrimitives
import com.itsluminous.cleartravel.core.security.vault.PortableKey
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer

/** Parsed header of a portable envelope — everything a reader needs besides the password. */
class PortableHeader internal constructor(
    val salt: ByteArray,
    val iterations: Int,
    val chunkSize: Int,
    val nonce: ByteArray,
    internal val bytes: ByteArray,
)

/** The stream is not a portable envelope (no magic) — treat it as a legacy plaintext file. */
class NotAnEnvelopeException : IOException("not a ClearTravel portable envelope")

/**
 * The portable password envelope (ADR-031, format **CTEB v1**) around anything that
 * LEAVES the device — backup ZIPs and per-file Drive uploads. Unlike CTEF it is keyed
 * by the password-derived [PortableKey], never by the per-install DEK, so a backup
 * restores on any install that knows the password:
 *
 * ```
 * "CTEB" (4) | version=1 (1) | iterations int32 BE (4) | salt (16) | chunkSize int32 BE (4) | nonce (8) | ChunkedAead body
 * ```
 *
 * The 37-byte header (salt + KDF parameters included) is the AAD of every chunk. A
 * reader parses the header first ([readHeader]) to decide whether a cached key
 * matches (`KeyVault.portableKeyFor`) or the source password must be requested.
 */
object PortableCipher {
    val MAGIC: ByteArray = "CTEB".encodeToByteArray()
    const val VERSION: Byte = 1
    const val HEADER_BYTES = 4 + 1 + Int.SIZE_BYTES + CryptoPrimitives.SALT_BYTES + Int.SIZE_BYTES + ChunkedAead.NONCE_BYTES
    private const val MAX_CHUNK_SIZE = 16 * 1024 * 1024

    /** Writes the header for [key] to [out] and returns the encrypting stream; close it to seal. */
    fun encryptingStream(
        out: OutputStream,
        key: PortableKey,
        chunkSize: Int = ChunkedAead.DEFAULT_CHUNK_SIZE,
    ): OutputStream {
        val nonce = CryptoPrimitives.randomBytes(ChunkedAead.NONCE_BYTES)
        val header =
            ByteBuffer
                .allocate(HEADER_BYTES)
                .put(MAGIC)
                .put(VERSION)
                .putInt(key.iterations)
                .put(key.salt)
                .putInt(chunkSize)
                .put(nonce)
                .array()
        out.write(header)
        return ChunkedAead.encryptingStream(out, key.key, nonce, header, chunkSize)
    }

    /** Whether [file] starts with the CTEB magic. */
    fun isEnvelope(file: File): Boolean {
        if (!file.isFile || file.length() < HEADER_BYTES) return false
        val head = ByteArray(MAGIC.size)
        file.inputStream().use { if (LocalFileCipher.readFully(it, head) < MAGIC.size) return false }
        return MAGIC.indices.all { head[it] == MAGIC[it] }
    }

    /**
     * Consumes exactly the header from [input].
     * @throws NotAnEnvelopeException when the magic is absent (legacy plaintext).
     */
    fun readHeader(input: InputStream): PortableHeader {
        val bytes = ByteArray(HEADER_BYTES)
        val read = LocalFileCipher.readFully(input, bytes)
        if (read < MAGIC.size || !MAGIC.indices.all { bytes[it] == MAGIC[it] }) throw NotAnEnvelopeException()
        if (read < HEADER_BYTES) throw IOException("truncated CTEB header")
        if (bytes[MAGIC.size] != VERSION) throw IOException("unsupported CTEB version ${bytes[MAGIC.size]}")
        val buffer = ByteBuffer.wrap(bytes, MAGIC.size + 1, HEADER_BYTES - MAGIC.size - 1)
        val iterations = buffer.int
        val salt = ByteArray(CryptoPrimitives.SALT_BYTES).also(buffer::get)
        val chunkSize = buffer.int
        val nonce = ByteArray(ChunkedAead.NONCE_BYTES).also(buffer::get)
        if (iterations <= 0 || chunkSize <= 0 || chunkSize > MAX_CHUNK_SIZE) throw IOException("bad CTEB header")
        return PortableHeader(salt, iterations, chunkSize, nonce, bytes)
    }

    fun readHeader(file: File): PortableHeader = file.inputStream().buffered().use(::readHeader)

    /**
     * Decrypting view of [input] positioned right after [header] (i.e. what
     * [readHeader] left). A wrong [key] fails on the first read with
     * [javax.crypto.AEADBadTagException].
     */
    fun decryptingStream(
        input: InputStream,
        header: PortableHeader,
        key: PortableKey,
    ): InputStream {
        require(key.matches(header.salt, header.iterations)) { "portable key does not match envelope header" }
        return ChunkedAead.decryptingStream(input, key.key, header.nonce, header.bytes, header.chunkSize)
    }

    /** Convenience: header + decrypting stream over a whole [file]. */
    fun openDecrypted(
        file: File,
        key: PortableKey,
    ): InputStream {
        val input = file.inputStream().buffered()
        try {
            val header = readHeader(input)
            return decryptingStream(input, header, key)
        } catch (e: Exception) {
            input.close()
            throw e
        }
    }
}
