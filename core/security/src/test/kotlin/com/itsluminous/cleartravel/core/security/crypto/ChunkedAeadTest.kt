package com.itsluminous.cleartravel.core.security.crypto

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import javax.crypto.AEADBadTagException
import kotlin.random.Random

class ChunkedAeadTest {
    private val key = CryptoPrimitives.randomKey()
    private val nonce = CryptoPrimitives.randomBytes(ChunkedAead.NONCE_BYTES)
    private val aad = "header".encodeToByteArray()

    private fun seal(
        plain: ByteArray,
        chunkSize: Int = 16,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        ChunkedAead.encryptingStream(out, key, nonce, aad, chunkSize).use { it.write(plain) }
        return out.toByteArray()
    }

    private fun open(
        sealed: ByteArray,
        chunkSize: Int = 16,
        useKey: javax.crypto.SecretKey = key,
    ): ByteArray = ChunkedAead.decryptingStream(ByteArrayInputStream(sealed), useKey, nonce, aad, chunkSize).use { it.readBytes() }

    @Test
    fun roundTrips_emptyAndExactMultipleAndOddSizes() {
        for (size in listOf(0, 1, 15, 16, 17, 32, 33, 1000)) {
            val plain = Random(size).nextBytes(size)
            assertThat(open(seal(plain))).isEqualTo(plain)
        }
    }

    @Test
    fun sealedSizeIsPlaintextPlusOneTagPerChunk() {
        // 40 bytes at chunk 16 → 16 + 16 + 8 = 3 chunks, 16-byte tag each.
        assertThat(seal(ByteArray(40)).size).isEqualTo(40 + 3 * 16)
        // Empty → one (empty) final chunk: just its tag.
        assertThat(seal(ByteArray(0)).size).isEqualTo(16)
    }

    @Test
    fun wrongKey_failsWithBadTag() {
        val sealed = seal("secret".encodeToByteArray())
        assertThrows(AEADBadTagException::class.java) { open(sealed, useKey = CryptoPrimitives.randomKey()) }
    }

    @Test
    fun truncationAtChunkBoundary_isDetected() {
        val sealed = seal(ByteArray(48)) // 3 full chunks, third is last
        val cut = sealed.copyOf(2 * 32) // drop the final chunk exactly at a boundary
        assertThrows(AEADBadTagException::class.java) { open(cut) }
    }

    @Test
    fun truncationMidChunk_isDetected() {
        val sealed = seal(ByteArray(48))
        val cut = sealed.copyOf(sealed.size - 5)
        assertThrows(AEADBadTagException::class.java) { open(cut) }
        assertThrows(EOFException::class.java) { open(sealed.copyOf(2 * 32 + 3)) }
    }

    @Test
    fun reorderedChunks_areRejected() {
        val sealed = seal(ByteArray(48) { it.toByte() })
        val swapped = sealed.copyOf()
        System.arraycopy(sealed, 0, swapped, 32, 32)
        System.arraycopy(sealed, 32, swapped, 0, 32)
        assertThrows(AEADBadTagException::class.java) { open(swapped) }
    }

    @Test
    fun singleByteReads_matchBulkReads() {
        val plain = Random(7).nextBytes(100)
        val stream = ChunkedAead.decryptingStream(ByteArrayInputStream(seal(plain)), key, nonce, aad, 16)
        val collected = ByteArrayOutputStream()
        while (true) {
            val b = stream.read()
            if (b < 0) break
            collected.write(b)
        }
        assertThat(collected.toByteArray()).isEqualTo(plain)
    }
}
