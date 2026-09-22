package com.itsluminous.cleartravel.core.security.file

import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.security.crypto.CryptoPrimitives
import com.itsluminous.cleartravel.core.security.vault.PortableKey
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import javax.crypto.AEADBadTagException
import kotlin.random.Random

class FileCiphersTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val fileKey = CryptoPrimitives.randomKey()
    private val cipher = LocalFileCipher(key = { fileKey }, chunkSize = 1024)

    @Test
    fun localFile_encryptDecryptRoundTrip_andHeaderDetection() {
        val plain = Random(1).nextBytes(5_000)
        val target = File(folder.root, "doc.pdf")
        cipher.encryptTo(ByteArrayInputStream(plain), target)

        assertThat(cipher.isEncrypted(target)).isTrue()
        assertThat(target.readBytes().copyOf(4)).isEqualTo("CTEF".encodeToByteArray())
        assertThat(target.length()).isEqualTo(LocalFileCipher.HEADER_BYTES + 5_000 + 5 * 16)
        assertThat(cipher.openDecrypted(target).use { it.readBytes() }).isEqualTo(plain)
        val out = ByteArrayOutputStream()
        cipher.decryptTo(target, out)
        assertThat(out.toByteArray()).isEqualTo(plain)
    }

    @Test
    fun plaintextFile_isPassedThrough_notFlaggedEncrypted() {
        val legacy = File(folder.root, "legacy.png").apply { writeBytes("not encrypted at all".encodeToByteArray()) }
        assertThat(cipher.isEncrypted(legacy)).isFalse()
        assertThat(cipher.openDecrypted(legacy).use { it.readBytes().decodeToString() }).isEqualTo("not encrypted at all")
        // Tiny files (shorter than a header) pass through too.
        val tiny = File(folder.root, "tiny").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        assertThat(cipher.openDecrypted(tiny).use { it.readBytes() }).isEqualTo(byteArrayOf(1, 2, 3))
        val empty = File(folder.root, "empty").apply { writeBytes(ByteArray(0)) }
        assertThat(cipher.openDecrypted(empty).use { it.readBytes() }).isEmpty()
    }

    @Test
    fun encryptInPlace_isIdempotent_oneTimeMigration() {
        val plain = Random(2).nextBytes(3_000)
        val file = File(folder.root, "pass.jpg").apply { writeBytes(plain) }

        assertThat(cipher.encryptInPlace(file)).isTrue()
        val encryptedBytes = file.readBytes()
        assertThat(cipher.encryptInPlace(file)).isFalse() // second run: already CTEF
        assertThat(file.readBytes()).isEqualTo(encryptedBytes)
        assertThat(cipher.openDecrypted(file).use { it.readBytes() }).isEqualTo(plain)
        assertThat(folder.root.listFiles()!!.map { it.name }).containsExactly("pass.jpg") // no temp left behind
    }

    @Test
    fun localFile_wrongKey_failsTyped() {
        val target = File(folder.root, "doc")
        cipher.encryptTo(ByteArrayInputStream(ByteArray(10)), target)
        val other = LocalFileCipher(key = { CryptoPrimitives.randomKey() })
        assertThrows(AEADBadTagException::class.java) { other.openDecrypted(target).use { it.readBytes() } }
    }

    private fun portableKey(
        salt: ByteArray = CryptoPrimitives.randomBytes(16),
        password: String = "pw",
    ): PortableKey = PortableKey(salt, 1_000, CryptoPrimitives.aesKey(CryptoPrimitives.pbkdf2(password.toCharArray(), salt, 1_000, 32)))

    @Test
    fun portableEnvelope_roundTrip_headerCarriesKdfInputs() {
        val key = portableKey()
        val plain = Random(3).nextBytes(70_000) // > one default chunk
        val out = ByteArrayOutputStream()
        PortableCipher.encryptingStream(out, key).use { it.write(plain) }
        val envelope = out.toByteArray()
        assertThat(envelope.copyOf(4)).isEqualTo("CTEB".encodeToByteArray())

        val input = ByteArrayInputStream(envelope)
        val header = PortableCipher.readHeader(input)
        assertThat(header.salt).isEqualTo(key.salt)
        assertThat(header.iterations).isEqualTo(1_000)
        assertThat(key.matches(header.salt, header.iterations)).isTrue()
        assertThat(PortableCipher.decryptingStream(input, header, key).use { it.readBytes() }).isEqualTo(plain)
    }

    @Test
    fun portableEnvelope_samePasswordOtherDevice_derivesSameKey_wrongPasswordFails() {
        val salt = CryptoPrimitives.randomBytes(16)
        val writer = portableKey(salt, "shared secret")
        val file = File(folder.root, "backup.zip")
        PortableCipher.encryptingStream(file.outputStream(), writer).use { it.write("payload".encodeToByteArray()) }
        assertThat(PortableCipher.isEnvelope(file)).isTrue()

        val header = PortableCipher.readHeader(file)
        val reader = portableKey(header.salt, "shared secret")
        assertThat(PortableCipher.openDecrypted(file, reader).use { it.readBytes().decodeToString() }).isEqualTo("payload")

        val wrong = portableKey(header.salt, "other")
        assertThrows(AEADBadTagException::class.java) { PortableCipher.openDecrypted(file, wrong).use { it.readBytes() } }
        val mismatched = portableKey(CryptoPrimitives.randomBytes(16), "shared secret")
        assertThrows(IllegalArgumentException::class.java) { PortableCipher.openDecrypted(file, mismatched) }
    }

    @Test
    fun portableEnvelope_plainZip_isNotAnEnvelope() {
        val zip = File(folder.root, "v1.zip").apply { writeBytes("PK\u0003\u0004rest-of-a-zip".encodeToByteArray()) }
        assertThat(PortableCipher.isEnvelope(zip)).isFalse()
        assertThrows(NotAnEnvelopeException::class.java) { PortableCipher.readHeader(zip) }
    }
}
