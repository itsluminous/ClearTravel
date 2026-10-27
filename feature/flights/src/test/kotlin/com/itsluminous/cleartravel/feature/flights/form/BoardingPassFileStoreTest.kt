package com.itsluminous.cleartravel.feature.flights.form

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.security.crypto.CryptoPrimitives
import com.itsluminous.cleartravel.core.security.file.LocalFileCipher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * ADR-045: one picked file imported for TWO flights is stored as two independent
 * encrypted copies (`boarding_passes/<flightId>.<ext>`), so deleting one flight's file
 * leaves the other's intact and decryptable. Real [LocalFileCipher] over a random key.
 */
@RunWith(RobolectricTestRunner::class)
class BoardingPassFileStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val cipher = LocalFileCipher(key = { KEY })
    private val store = BoardingPassFileStore(context, cipher)

    @Test
    fun `the same file stored for two flights yields two independent encrypted copies`() =
        runTest {
            val bytes = ByteArray(5_000) { (it % 251).toByte() }
            val picked = File(context.cacheDir, "picked-pass.png").apply { writeBytes(bytes) }

            val first = store.store(Uri.fromFile(picked).toString(), "flight-1")!!
            val second = store.store(Uri.fromFile(picked).toString(), "flight-2")!!

            assertThat(first).endsWith("flight-1.png")
            assertThat(second).endsWith("flight-2.png")
            assertThat(first).isNotEqualTo(second)
            assertThat(cipher.isEncrypted(File(first))).isTrue()
            assertThat(cipher.isEncrypted(File(second))).isTrue()

            // Deleting flight 1's copy leaves flight 2's readable, byte for byte.
            assertThat(File(first).delete()).isTrue()
            val out = ByteArrayOutputStream()
            cipher.decryptTo(File(second), out)
            assertThat(out.toByteArray()).isEqualTo(bytes)
        }

    @Test
    fun `an unreadable uri stores nothing`() =
        runTest {
            assertThat(store.store("file:///nonexistent/nowhere.pdf", "flight-x")).isNull()
        }

    private companion object {
        val KEY = CryptoPrimitives.randomKey()
    }
}
