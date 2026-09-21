package com.itsluminous.cleartravel.core.ocr

import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.itsluminous.cleartravel.core.ocr.extract.IrctcTicketExtractor
import com.itsluminous.cleartravel.core.ocr.pipeline.BitmapPreprocessor
import com.itsluminous.cleartravel.core.ocr.pipeline.OcrTextRecognizer
import com.itsluminous.cleartravel.core.ocr.pipeline.PdfPageRasterizer
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import org.junit.Assume.assumeTrue
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * DEVICE-ONLY capture harness, not a regression test (hence @Ignore): runs a real
 * ticket file through the SAME pipeline as `OcrPrefillService.prefillTrainTicket`
 * (rasterize -> preprocess -> ML Kit) and logs the RAW recognized text plus the
 * extraction under [TAG], so a new extractor fixture can be recorded from what
 * ML Kit actually sees (the PDF text layer is NOT what the extractor gets).
 *
 * Usage:
 * ```
 * adb push ticket.pdf /sdcard/Android/data/com.itsluminous.cleartravel.core.ocr.test/files/capture.pdf
 * ./gradlew :core:ocr:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=com.itsluminous.cleartravel.core.ocr.OcrCaptureHarnessTest \
 *   -Pandroid.testInstrumentationRunnerArguments.notIgnored=true   # or drop @Ignore locally
 * adb logcat -d -s OcrCapture
 * ```
 * Anonymize names before committing the captured text as a fixture.
 */
@Ignore("Manual capture harness — remove @Ignore locally to record OCR text from a device.")
@RunWith(AndroidJUnit4::class)
class OcrCaptureHarnessTest {
    @Test
    fun captureTrainTicketOcr() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val file = File(context.getExternalFilesDir(null), CAPTURE_FILE)
        assumeTrue("No capture file at ${file.absolutePath}", file.exists())

        val client = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val recognizer = OcrTextRecognizer(client)
        val bitmap =
            context.contentResolver.openFileDescriptor(Uri.fromFile(file), "r")!!.use {
                PdfPageRasterizer().rasterize(it)
            }
        val processed = BitmapPreprocessor().preprocess(bitmap)
        val raw = runBlocking { client.process(InputImage.fromBitmap(processed, 0)).await() }
        Log.i(TAG, "===== GEOMETRY (w=${processed.width} h=${processed.height}) =====")
        raw.textBlocks.flatMap { it.lines }.forEach { line ->
            val b = line.boundingBox
            Log.i(TAG, "G|${b?.left}|${b?.top}|${b?.right}|${b?.bottom}|${line.text}")
        }
        val text = runBlocking { recognizer.recognize(processed) }
        Log.i(TAG, "===== RAW OCR TEXT BEGIN (${text.length} chars) =====")
        text.lines().forEachIndexed { index, line -> Log.i(TAG, "L%03d|%s".format(index, line)) }
        Log.i(TAG, "===== RAW OCR TEXT END =====")
        Log.i(TAG, "EXTRACTION: ${IrctcTicketExtractor().extract(text)}")
    }

    private companion object {
        const val TAG = "OcrCapture"
        const val CAPTURE_FILE = "capture.pdf"
    }
}
