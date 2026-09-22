package com.itsluminous.cleartravel.feature.documents

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.data.repository.TravelDocumentStorage
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalDocumentFileStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val store = LocalDocumentFileStore(context)

    @After
    fun tearDown() {
        TravelDocumentStorage.directory(context.filesDir).deleteRecursively()
    }

    @Test
    fun `store copies the picked bytes into filesDir documents named by id and extension`() =
        runTest {
            val source = File(context.cacheDir, "visa-scan.pdf").apply { writeBytes(byteArrayOf(1, 2, 3)) }

            val stored = store.store(Uri.fromFile(source).toString(), "doc-1")

            assertThat(stored).isNotNull()
            val file = File(stored!!.path)
            assertThat(file.parentFile).isEqualTo(TravelDocumentStorage.directory(context.filesDir))
            assertThat(file.name).isEqualTo("doc-1.pdf")
            assertThat(file.readBytes()).isEqualTo(byteArrayOf(1, 2, 3))
        }

    @Test
    fun `store returns null for an unreadable uri and delete tolerates missing files`() =
        runTest {
            assertThat(store.store("content://nonexistent.provider/doc/42", "doc-2")).isNull()
            assertThat(TravelDocumentStorage.directory(context.filesDir).listFiles().orEmpty()).isEmpty()

            store.delete(File(context.filesDir, "documents/never-there.jpg").absolutePath)
        }

    @Test
    fun `delete removes a stored file`() =
        runTest {
            val source = File(context.cacheDir, "id.jpg").apply { writeBytes(byteArrayOf(9)) }
            val stored = store.store(Uri.fromFile(source).toString(), "doc-3")!!
            assertThat(File(stored.path).exists()).isTrue()

            store.delete(stored.path)

            assertThat(File(stored.path).exists()).isFalse()
        }
}
