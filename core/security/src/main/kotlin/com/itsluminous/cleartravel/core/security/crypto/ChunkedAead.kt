package com.itsluminous.cleartravel.core.security.crypto

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import javax.crypto.SecretKey

/**
 * Streaming AES-256-GCM in fixed-size chunks (ADR-031) — the body shared by the
 * on-device file format (`LocalFileCipher`) and the portable password envelope
 * (`PortableCipher`).
 *
 * Layout after the format-specific header: a sequence of GCM-sealed chunks of
 * [chunkSize] plaintext bytes each (the final chunk shorter, possibly empty). Chunk
 * `i` is sealed with IV = `nonce(8) ‖ i(4, big-endian)` and AAD = `header ‖ isLast(1)`,
 * so chunks cannot be reordered, dropped, or truncated without a tag failure — the
 * `isLast` flag makes cutting the stream after a complete chunk detectable. A
 * one-shot `Cipher` per chunk keeps memory bounded at one chunk regardless of the
 * provider's GCM buffering behaviour, and every read error surfaces as a typed
 * [javax.crypto.AEADBadTagException] (wrong key / tampered) or [IOException] (truncated).
 */
object ChunkedAead {
    const val DEFAULT_CHUNK_SIZE = 64 * 1024
    const val NONCE_BYTES = 8
    private const val TAG_BYTES = CryptoPrimitives.GCM_TAG_BITS / 8

    fun encryptingStream(
        out: OutputStream,
        key: SecretKey,
        nonce: ByteArray,
        aad: ByteArray,
        chunkSize: Int = DEFAULT_CHUNK_SIZE,
    ): OutputStream = EncryptingStream(out, key, nonce, aad, chunkSize)

    fun decryptingStream(
        input: InputStream,
        key: SecretKey,
        nonce: ByteArray,
        aad: ByteArray,
        chunkSize: Int = DEFAULT_CHUNK_SIZE,
    ): InputStream = DecryptingStream(input, key, nonce, aad, chunkSize)

    internal fun chunkIv(
        nonce: ByteArray,
        index: Int,
    ): ByteArray {
        require(nonce.size == NONCE_BYTES) { "nonce must be $NONCE_BYTES bytes" }
        return ByteBuffer
            .allocate(CryptoPrimitives.GCM_IV_BYTES)
            .put(nonce)
            .putInt(index)
            .array()
    }

    internal fun chunkAad(
        aad: ByteArray,
        isLast: Boolean,
    ): ByteArray = aad + byteArrayOf(if (isLast) 1 else 0)

    private class EncryptingStream(
        private val out: OutputStream,
        private val key: SecretKey,
        private val nonce: ByteArray,
        private val aad: ByteArray,
        private val chunkSize: Int,
    ) : OutputStream() {
        private val buffer = ByteArray(chunkSize)
        private var buffered = 0
        private var index = 0
        private var closed = false

        override fun write(b: Int) {
            write(byteArrayOf(b.toByte()), 0, 1)
        }

        override fun write(
            source: ByteArray,
            offset: Int,
            length: Int,
        ) {
            check(!closed) { "stream closed" }
            var position = offset
            var remaining = length
            while (remaining > 0) {
                val take = minOf(remaining, chunkSize - buffered)
                System.arraycopy(source, position, buffer, buffered, take)
                buffered += take
                position += take
                remaining -= take
                // A full buffer is only flushed once MORE data arrives, so the final
                // chunk (sealed with isLast) is always the one written by close().
                if (buffered == chunkSize && remaining > 0) sealChunk(isLast = false)
            }
        }

        override fun flush() {
            out.flush()
        }

        override fun close() {
            if (closed) return
            closed = true
            try {
                sealChunk(isLast = true)
                out.flush()
            } finally {
                out.close()
            }
        }

        private fun sealChunk(isLast: Boolean) {
            val plaintext = buffer.copyOf(buffered)
            val sealed = CryptoPrimitives.gcmEncrypt(key, chunkIv(nonce, index), plaintext, chunkAad(aad, isLast))
            out.write(sealed)
            index++
            buffered = 0
        }
    }

    private class DecryptingStream(
        private val input: InputStream,
        private val key: SecretKey,
        private val nonce: ByteArray,
        private val aad: ByteArray,
        chunkSize: Int,
    ) : InputStream() {
        private val sealedSize = chunkSize + TAG_BYTES
        private val sealed = ByteArray(sealedSize)
        private var plain: ByteArray = ByteArray(0)
        private var plainPos = 0
        private var index = 0
        private var finished = false
        private var pending: Int = -2 // -2 = nothing peeked, -1 = EOF peeked, else a byte

        override fun read(): Int {
            val one = ByteArray(1)
            val n = read(one, 0, 1)
            return if (n <= 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(
            target: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            if (length == 0) return 0
            while (plainPos >= plain.size) {
                if (finished) return -1
                openNextChunk()
            }
            val n = minOf(length, plain.size - plainPos)
            System.arraycopy(plain, plainPos, target, offset, n)
            plainPos += n
            return n
        }

        override fun close() {
            input.close()
        }

        private fun openNextChunk() {
            val got = fillSealed()
            if (got < TAG_BYTES) throw EOFException("encrypted stream truncated")
            val isLast = got < sealedSize || peekIsEof()
            // A wrong key, tampering, or a cut at a chunk boundary all surface here as
            // AEADBadTagException (the isLast flag is part of the AAD).
            plain = CryptoPrimitives.gcmDecrypt(key, chunkIv(nonce, index), sealed.copyOf(got), chunkAad(aad, isLast))
            plainPos = 0
            index++
            finished = isLast
        }

        /** Reads up to one sealed chunk, honouring a byte left over from the last peek. */
        private fun fillSealed(): Int {
            var filled = 0
            if (pending >= 0) {
                sealed[0] = pending.toByte()
                filled = 1
                pending = -2
            } else if (pending == -1) {
                return 0
            }
            while (filled < sealedSize) {
                val n = input.read(sealed, filled, sealedSize - filled)
                if (n < 0) break
                filled += n
            }
            return filled
        }

        private fun peekIsEof(): Boolean {
            val next = input.read()
            pending = if (next < 0) -1 else next
            return next < 0
        }
    }
}
